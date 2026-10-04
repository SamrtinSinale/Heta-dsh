package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「读一次活清单」的执行者：起一次随包 dsh，让它自己算一遍 Loader 清单并打一行 JSON。
 *
 * 为什么必须真起一次 dsh：那份数据（配置状态 / 运行状态 / 预设）只有 dsh 进程内的
 * `readPluginInventory` 算得出来，而 ACP 通道**没有** remote 出口（随包的 `dsh-acp/lib` 里
 * `typert` / `remote` / `pluginInventory` 零命中）。挂进去的桥插件见
 * `assets/heta-inventory-bridge.mjs`。
 *
 * 三件事和 [DshRuntimeConfig.rootScript] 是**同源**的（改了那边要一起看）：
 *   1. `--expose-internals`：dsh 0.2 靠它读 Node 内部模块。少了它，dsh 在 host preparation
 *      阶段就退出，报 `internal module access requires --expose-internals`（本机实测的那条）。
 *   2. `/dev` 与 `/proc` 的挂载条件（`dev/pts/ptmx` / `proc/self` 不存在才挂）。
 *   3. 环境：`HOME=/root`、`PATH`、`LANG=C.UTF-8`、`DSH_PERMISSION_MODE`。
 *
 * 脚本**由 [probeScript] 生成**而不是 assets 里的静态文件：启动命令必须和真实启动路径同源 ——
 * `scripts/test-dsh-e2e.sh` 的头一条教训就是"端到端不自己拼启动命令，拼就会漏掉真实路径"。
 * 生成出来的那份由单测导出到 `app/build/dsh-probe.sh`，CI（qemu + chroot）跑的就是它。
 */
internal object DshInventoryProbe {

    private const val TAG = "DshInventoryProbe"

    /** 桥的资产名 —— 也是它在 `<root>/opt/dsh/` 下的文件名（覆盖层里写的是相对名）。 */
    private const val BRIDGE_ASSET = "heta-inventory-bridge.mjs"

    /** 桥在 Loader 里的条目 id（覆盖层里的 `- id:`）。 */
    private const val BRIDGE_ENTRY_ID = "heta-inventory-bridge"

    private const val OVERLAY_NAME = "heta-inventory-overlay.patch.yml"

    /** 覆盖层在 chroot **内**的路径（`exec chroot` 之后才成立）。 */
    private const val OVERLAY_IN_ROOT = "/opt/dsh/$OVERLAY_NAME"

    private const val BRIDGE_RELATIVE = "opt/dsh/$BRIDGE_ASSET"
    /** join 插件与覆盖层同目录：覆盖层里那一行写的是相对名。 */
    private const val JOIN_RELATIVE = "opt/dsh/heta-preset-join.mjs"
    private const val SCRIPT_RELATIVE = "opt/dsh/heta-inventory-probe.sh"

    /** 生成期占位符。**不是** shell 变量：生成后文本里一个 `@` 都不留。 */
    private const val ROOT_DEFAULT_SLOT = "@HETA_ROOT_DEFAULT@"
    private const val PATH_SLOT = "@HETA_PATH@"
    private const val MODE_SLOT = "@HETA_MODE@"
    private const val OVERLAY_SLOT = "@HETA_OVERLAY@"

    /**
     * 探针的墙上时间上限。
     *
     * 必须**比桥自己的超时（90 秒）长**：桥超时会先打一份 `timedOut` 快照再退，而那份快照在
     * App 侧算失败（不完整的清单按完整显示等于撒谎）。这里要是先到，就变成"连快照都没有"，
     * 界面上能看到的理由少一大截。
     *
     * 120 秒是量出来的余量：真 arm64 + qemu 上整条脚本 27 秒（2026-10-04 实测）。超时本身**不是**
     * 判据 —— 判据在桥那边（条目数稳定），这里只是不让界面无限等 su 授权弹窗。
     */
    private const val TIMEOUT_MS = 120_000L

    /** stderr 尾部带几行给界面看；多了会把真正的错因埋掉。 */
    private const val STDERR_TAIL_LINES = 20

    private const val SU = "su"

    /** 与 `DshRuntimeConfig.rootScript()` 同源的命令名：`su -c` 走的还是 `sh`。 */
    private const val HOST_SHELL = "sh"

    /**
     * 读一次活清单。**不抛异常**：任何失败都变成 [DshLiveInventory.Failed]，
     * 界面据此退回文件视图（这个页面的底线是不能因为探针失败而空白）。
     */
    fun read(context: Context): DshLiveInventory {
        val root = DshRuntimeInstaller.runtimeDirectory(context)
        // 运行时不在就别起进程：那种情况下 su 只会以一句找不到 node 结束，比这里直接说清楚难查。
        if (!DshRuntimeInstaller.isReady(context)) {
            return DshLiveInventory.Failed("对话运行时还没装好，读不到活清单")
        }
        val logs = runCatching {
            File.createTempFile("dsh-inventory-out-", ".log") to
                File.createTempFile("dsh-inventory-err-", ".log")
        }.getOrElse { error ->
            Log.w(TAG, "探针的临时输出文件建不出来", error)
            return DshLiveInventory.Failed("建不了探针的临时输出文件：${describe(error)}")
        }
        val stdoutFile = logs.first
        val stderrFile = logs.second
        try {
            // 预设平面与真启动共用一份装配（DshPresetPlane），探针报的才是真世界。
            val plane = DshPresetPlane.overlayFor(context, DshPresetPlane.selectedFor(context))
            val script = prepare(context, root, plane)
            // stdout / stderr 都重定向到**文件**而不是管道：dsh 的日志量没有上限，而管道只有
            // 64KB —— 子进程写满就会卡在 write 上永远不退出，那时这里只看到"超时"，真正的原因
            //（输出太多）反而丢了。同一个理由见 `DshRuntimeInstaller.purgeLeftovers`。
            val started = ProcessBuilder(SU, "-c", command(script, root))
                .redirectOutput(stdoutFile)
                .redirectError(stderrFile)
                .start()
            if (!started.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                started.destroyForcibly()
                return DshLiveInventory.Failed(
                    "探针超时（${TIMEOUT_MS / 1000} 秒）：su 可能卡在授权，或 dsh 没能把清单打出来",
                )
            }
            val stdout = runCatching { stdoutFile.readText() }.getOrDefault("")
            val stderr = runCatching { stderrFile.readText() }.getOrDefault("")
            return when (val parsed = DshLiveInventoryCodec.parse(stdout)) {
                is DshLiveInventory.Ready -> parsed
                // 失败时把 stderr 尾巴接上：真正的原因（原生模块加载失败、YAML 坏了、su 报错）
                // 几乎只在 stderr 里，界面上要看得见。
                is DshLiveInventory.Failed -> DshLiveInventory.Failed(
                    buildString {
                        append(parsed.reason)
                        append("（dsh 退出码 ${started.exitValue()}）")
                        stderrTail(stderr)?.let { append("；stderr 末尾：\n").append(it) }
                    },
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "活清单探针失败", error)
            return DshLiveInventory.Failed("探针没跑起来：${describe(error)}")
        } finally {
            runCatching { stdoutFile.delete() }
            runCatching { stderrFile.delete() }
        }
    }

    /**
     * 把桥资产与探针脚本落进运行时目录（每次覆盖写，0644）。
     *
     * 桥从 `assets` 读出来落盘而不是在 Kotlin 里再写一份字面量：资产改了，dsh 侧跑的就是新的
     * 那一份，两边不可能漂移。
     *
     * 脚本写成 0644 而不是 0755：它由 `su -c "sh <脚本> <root>"` 读着执行（见 [command]），
     * POSIX 的 execve 才要求 x 位。少一个权限位就少一处"为什么这个文件是可执行的"的疑问。
     */
    private fun prepare(context: Context, root: File, presetPlane: String): File {
        val script = File(root, SCRIPT_RELATIVE)
        script.parentFile?.mkdirs()
        script.writeText(
            probeScript(
                root,
                DshRuntimeConfig.PATH_IN_ROOT,
                DshRuntimeConfig.PERMISSION_MODE,
                presetPlane,
            )
        )
        readableByAll(script)
        val bridge = File(root, BRIDGE_RELATIVE)
        context.assets.open(BRIDGE_ASSET).use { input ->
            bridge.outputStream().use { output -> input.copyTo(output) }
        }
        readableByAll(bridge)
        // join 插件也要在 root 里：覆盖层那一行是相对名，必须和覆盖层同目录。探针里它不会被
        // 触发（没有 Agent 被创建），但"真启动有的东西探针也要有" —— 否则探针报的是另一个世界，
        // 而"注册表在、却没人给会话绑预设"这种差异恰恰是最该看得见的那一类。
        val join = DshPresetPlane.joinPluginFor(context)
        if (join.isNotEmpty()) {
            val file = File(root, JOIN_RELATIVE)
            file.writeText(join)
            readableByAll(file)
        }
        return script
    }

    /** `su -c` 的整段命令。两个路径都可能含空格（用户目录/包名不受我们控制），一律引号。 */
    private fun command(script: File, root: File): String =
        "$HOST_SHELL ${DshRuntimeConfig.shellQuote(script.absolutePath)} " +
            DshRuntimeConfig.shellQuote(root.absolutePath)

    /**
     * 落成 0644（属主可读写，其他只读）。
     *
     * 用 java.io 这套老写法而不是 `android.system.Os.chmod`：`Os` 是 android.* API，用它这个类
     * 就没法在纯 JVM 单测里跑了（同 [DshProfileStore] 的 ownerOnly）。在 POSIX 上它等价于
     * `chmod 644`；在别的平台上退化成功效不明的"尽力而为" —— 失败不影响后续步骤。
     */
    private fun readableByAll(file: File) {
        runCatching {
            file.setReadable(true, false)
            file.setWritable(false, false)
            file.setWritable(true, true)
            file.setExecutable(false, false)
        }
    }

    /** stderr 末尾若干**非空**行；没有可看的内容就返回 null。 */
    private fun stderrTail(stderr: String): String? {
        val lines = stderr.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return lines.takeLast(STDERR_TAIL_LINES).joinToString("\n")
    }

    /**
     * 取清单用的覆盖层。
     *
     * 两件事都是必须的：
     *   · `- id: acp` + `disabled: true`：ACP 桥占着 stdio。探针要的是"dsh 挂完插件后自己打一行
     *     JSON 退出"，不是跑 ACP 会话 —— 不关它，ACP 那边会一直等客户端，永远等不到清单。
     *   · `insert:`：把桥挂进 Loader。名字写成 `./heta-inventory-bridge.mjs`（相对名）—— dsh 会
     *     按**覆盖层文件所在目录**把它解析成 `file://` URL，所以桥必须和覆盖层放在同一个目录
     *     （就是 `<root>/opt/dsh/`）。写绝对路径反而要跟着 chroot 内外两套路径走，更容易错。
     */
    internal fun probeOverlay(presetPlane: String = ""): String = buildString {
        append("- id: acp\n")
        append("  disabled: true\n")
        append("- insert:\n")
        append("    - id: $BRIDGE_ENTRY_ID\n")
        append("      name: ./$BRIDGE_ASSET\n")
        // 预设平面（Host 行 → 注册表 → 四份官方声明 → agent 平面让位）与真启动**同一份文本**：
        // 探针要报的是真实对话会遇到的这个世界。少了它，页面上看到的 agent 平面是"根上活着"，
        // 而真跑起来那 24 行是被预设接管的 —— 两套口径，比不显示更糟。
        if (presetPlane.isNotBlank()) {
            append(presetPlane.trimEnd()).append('\n')
        }
    }

    /**
     * 生成探针脚本的全文。
     *
     * 形制逐字照 [DshRuntimeConfig.rootScript]（挂载条件、`exec chroot`、参数），差别只有三处，
     * 都是"取清单"这件事本身要求的：① 先写一份取清单用的覆盖层；② 不 bind 技能库、不 source
     * 凭据（清单与凭据无关）；③ 脚本自带 `#!/bin/sh`，因为它会被导出给 CI 直接跑。
     *
     * 三个参数都是**生成期**就固定的：默认 root、PATH、权限模式。
     */
    internal fun probeScript(
        runtimeRoot: File,
        pathValue: String,
        permissionMode: String,
        presetPlane: String = "",
    ): String =
        PROBE_SCRIPT_TEMPLATE
            .replace(ROOT_DEFAULT_SLOT, DshRuntimeConfig.shellQuote(runtimeRoot.absolutePath))
            .replace(PATH_SLOT, DshRuntimeConfig.shellQuote(pathValue))
            .replace(MODE_SLOT, DshRuntimeConfig.shellQuote(permissionMode))
            .replace(OVERLAY_SLOT, probeOverlay(presetPlane))

    private fun describe(error: Throwable): String =
        error.message ?: error::class.java.simpleName
}

/**
 * 探针脚本模板。
 *
 * **行首不留缩进是有意的**：这份文本要逐字落盘给 `sh` 跑，用 `trimIndent()` 换算缩进会让
 * "源码里看到的"和"落盘的"差一层，而 CI 跑的就是导出的那一份
 *（见 `DshInventoryProbeScriptTest`）。所以模板整体顶格写，只靠 [String.trimStart] 去掉
 * 开头那个换行。
 *
 * `@X@` 是生成期占位符（**不是** shell 变量）；`${'$'}` 是 Kotlin 里字面 `$` 的标准写法 ——
 * 脚本里的 `$ROOT` 必须保持字面，不能变成 Kotlin 模板。
 */
private val PROBE_SCRIPT_TEMPLATE = """
#!/bin/sh
#
# Heta「扩展」页的**活清单探针**：起一次 dsh，让它自己算一遍 Loader 清单并打一行 JSON。
#
# 为什么要有它：官方那页的数据（配置状态 / 运行状态 / 预设）来自 dsh 进程内的
# readPluginInventory，而 ACP 通道没有 remote 出口。所以只能自己起一次 dsh，用 --patch
# 覆盖层把打 JSON 的桥插件挂进去。
#
# 为什么由 Kotlin 生成、而不是 assets 里的静态脚本：启动命令必须与真实启动路径同源。
# scripts/test-dsh-e2e.sh 的头一条教训就是"端到端不自己拼启动命令 —— 拼就会漏掉真实路径"。
# 所以这里的挂载条件与 exec chroot 的形制逐字来自 DshRuntimeConfig.rootScript()，
# 单测把这份全文导出到 app/build/dsh-probe.sh，CI 跑的就是那一份。
#
# 参数：${'$'}1 = runtime root 的**宿主**路径。不传就用生成时钉住的那个
#（App 生成时钉的就是它自己那棵树，所以 App 侧传不传等价；CI 解包到别处，于是必须传）。
# 一律用 POSIX sh：Android 的 sh 是 mksh 或 busybox，这里不许出现 bash 专属语法。
#
set -u

ROOT="${'$'}{1:-}"
[ -n "${'$'}ROOT" ] || ROOT=@HETA_ROOT_DEFAULT@

# 环境对齐 DshRuntimeConfig：rootScript() 里那行 export（HOME / PATH / LANG / DSH_PERMISSION_MODE）
# 再补两个同一件事上的键。都不是可选项，各有来历：
#   · DSH_HOME  —— 官方默认是 ${'$'}HOME/.dsh；这份脚本会被导出到 chroot 之外跑，明写就省掉
#                  "哪来的 HOME、默认值解析成什么"这一层推断。
#   · TMPDIR    —— 与 DshRuntimeConfig.environment() 同一个理由：App 进程的环境会整份继承下去，
#                  Android 侧 TMPDIR 是 /data/user/0/<包名>/cache，chroot 里**不存在**，
#                  于是 dsh 的 spill 目录建不出来，只留一句 "did not activate" 的警告，
#                  大段工具输出就没地方溢写。钉成 chroot 内的 /tmp。
export HOME=/root PATH=@HETA_PATH@ LANG=C.UTF-8 DSH_PERMISSION_MODE=@HETA_MODE@ DSH_HOME=/root/.dsh TMPDIR=/tmp

# ① 取清单专用的覆盖层。
#    相对名按**覆盖层所在目录**解析（dsh 的 anchorInsertedPluginNames），所以桥和覆盖层
#    必须在同一个目录里 —— 这里就是 ROOT/opt/dsh。
#    关掉 acp：它占着 stdio，而探针要的是"dsh 挂完插件自己打一行 JSON 退出"，
#    不是跑 ACP 会话（不关它，ACP 会一直等客户端，清单永远出不来）。
cat > "${'$'}ROOT/opt/dsh/heta-inventory-overlay.patch.yml" <<'HETA_OVERLAY'
@HETA_OVERLAY@HETA_OVERLAY

# ② /dev：dsh 的子进程走 node-pty，需要 /dev/ptmx 与 /dev/pts。条件与 rootScript() 一致
#   （已经挂过就不重复挂），失败不阻断 —— 清单本身不依赖 pty。
if [ ! -e "${'$'}ROOT/dev/pts/ptmx" ]; then mount --rbind /dev "${'$'}ROOT/dev" 2>/dev/null; fi

# ③ /proc：ripgrep 之类要读 /proc/self/exe。同样只在没挂过时挂，失败不阻断。
if [ ! -e "${'$'}ROOT/proc/self" ]; then mount -t proc proc "${'$'}ROOT/proc" 2>/dev/null; fi

# ④ 起 dsh。--expose-internals 是 dsh 0.2 读 Node 内部模块的必需参数：少了它 dsh 在
#    host preparation 阶段就退出，报 "internal module access requires --expose-internals"
#   （本机实测过的那条）。
exec chroot "${'$'}ROOT" /opt/node/bin/node --expose-internals /opt/dsh/lib/bin.js --profile acp --patch /opt/dsh/heta-inventory-overlay.patch.yml
""".trimStart()
