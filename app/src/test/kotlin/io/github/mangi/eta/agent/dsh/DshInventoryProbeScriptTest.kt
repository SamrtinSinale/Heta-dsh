package io.github.mangi.eta.agent.dsh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 探针脚本生成成了什么形状。
 *
 * 为什么这些断言值得写：`scripts/test-dsh-live-inventory.sh` 在 CI 里跑的**不是它自己拼的
 * 命令**，而是这里导出到 `app/build/dsh-probe.sh` 的那一份（同 `test-dsh-e2e.sh` 用单测导出的
 * 启动脚本那条原则 —— 端到端自己拼命令就会漏掉真实路径，上次已经吃过亏）。所以脚本里少一个
 * 挂载条件、少一个 `--expose-internals`、路径拼错一层，都只有在这里才抓得住。
 *
 * 纯 JVM：只拼字符串，不需要 Robolectric。
 */
class DshInventoryProbeScriptTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 只建一次：`newFolder` 同名调第二次会抛。 */
    private val runtimeRoot: File by lazy { temporaryFolder.newFolder("dsh-runtime") }

    /**
     * 资产读成文本：单测的工作目录是 `app/`，所以与生产 `Context.assets` 读的是**同一个文件**。
     *
     * 这条很重要：`scripts/test-dsh-live-inventory.sh` 在 CI 里跑的就是这里导出的脚本，于是
     * CI 会连带把**预设平面**（Host 行 / 注册表 / 四份官方声明 / agent 平面让位）一起验掉 ——
     * 而不是验一个"没有预设的世界"。
     */
    private fun asset(path: String): String = File("src/main/assets", path).readText()

    /** 一份**有辨识度**的假人格后缀：注入逻辑有没有生效，看它在不在覆盖层里就够了。 */
    private val deploymentSuffix = listOf(
        "HETA-PROBE-IDENTITY",
        "",
        "Your working directory is {{cwd}}.",
        "HETA-PROBE-SKILLS",
    )

    private fun presetPlane(): String = DshPresetPlane.overlay(
        { path -> asset(path) },
        DshPresetPlane.DEFAULT_PRESET,
        deploymentSuffix,
    )

    private fun script(): String = DshInventoryProbe.probeScript(
        runtimeRoot = runtimeRoot,
        pathValue = DshRuntimeConfig.PATH_IN_ROOT,
        permissionMode = "danger-full-access",
        presetPlane = presetPlane(),
    )

    @Test
    fun scriptRunsTheSameStartupPathAsTheRealRuntime() {
        val script = script()

        // 起 dsh 的那一行必须与 DshRuntimeConfig.rootScript() 同源：同样的入口、同样的
        // --expose-internals（少了它 dsh 在 host preparation 就退出）、同样的 profile 与覆盖层。
        assertTrue(
            "exec chroot 那一行不对：\n$script",
            script.contains(
                "exec chroot \"\$ROOT\" /opt/node/bin/node --expose-internals " +
                    "/opt/dsh/lib/bin.js --profile acp --patch /opt/dsh/heta-inventory-overlay.patch.yml",
            ),
        )
        // 只要求"含这个 PATH"，不管生成器给不给它加引号：钉死引号形状没有意义，
        // 而 PATH 本身必须是真实启动脚本用的那一条（漏 /usr/sbin 会让 exec chroot 找不到 chroot，
        // CI 上就是这样红的：退出码 127）。
        assertTrue("PATH 不是生成期传进来的那个：\n$script", script.contains(DshRuntimeConfig.PATH_IN_ROOT))
        assertTrue(
            "权限模式不是生成期传进来的那个：\n$script",
            script.contains("DSH_PERMISSION_MODE='danger-full-access'"),
        )
        // DSH_HOME 明写：官方默认是 $HOME/.dsh，但这份脚本会被导出到 chroot 之外跑，
        // 让"默认值解析成什么"变成推断就不值得了。TMPDIR 钉成 chroot 内的 /tmp：App 的 TMPDIR
        // 是宿主路径，漏进 chroot 会让 dsh 的 spill 目录建不出来（DshRuntimeConfig 里踩过）。
        assertTrue("没明写 DSH_HOME：\n$script", script.contains("DSH_HOME=/root/.dsh"))
        assertTrue("没把 TMPDIR 钉在 chroot 内的 /tmp：\n$script", script.contains("TMPDIR=/tmp"))
        // 只看 export 行：脚本里的**注释**会拿 Android 的 …/cache 举例说明为什么要钉 TMPDIR，
        // 扫全文会把那段解释当成泄漏（这条断言第一版就是这么误报的）。
        val exportLine = script.lineSequence().first { it.startsWith("export HOME=") }
        assertFalse("宿主的 TMPDIR 漏进了 export 行：$exportLine", exportLine.contains("/cache"))
        assertTrue("没钉住 HOME：\n$script", script.contains("HOME=/root"))
        assertTrue("没钉住 LANG：\n$script", script.contains("LANG=C.UTF-8"))
    }

    @Test
    fun scriptMountsDevAndProcUnderTheSameConditionsAsTheRealRuntime() {
        val script = script()

        // 条件逐字照 rootScript()：**已经挂过就不重复挂**，且失败不阻断（没有 set -e）。
        assertTrue(
            "/dev 的挂载条件与真实启动脚本不一致：\n$script",
            script.contains("if [ ! -e \"\$ROOT/dev/pts/ptmx\" ]; then mount --rbind /dev \"\$ROOT/dev\" 2>/dev/null; fi"),
        )
        assertTrue(
            "/proc 的挂载条件与真实启动脚本不一致：\n$script",
            script.contains("if [ ! -e \"\$ROOT/proc/self\" ]; then mount -t proc proc \"\$ROOT/proc\" 2>/dev/null; fi"),
        )
        assertFalse("探针脚本不该有 set -e：挂载失败会把它一起带走\n$script", script.contains("set -e"))
    }

    @Test
    fun scriptTakesTheRuntimeRootAsAnArgument() {
        val script = script()

        // 参数化是 CI 能用同一份脚本的关键：CI 解包到自己的临时目录，App 用自己那棵树。
        assertTrue("没有从 \$1 取 root：\n$script", script.contains("ROOT=\"\${1:-}\""))
        assertTrue(
            "生成期钉住的默认 root 丢了：\n$script",
            script.contains("|| ROOT='${runtimeRoot.absolutePath}'"),
        )
    }

    @Test
    fun everyRuntimeRootReferenceIsQuoted() {
        val script = script()
        val occurrences = Regex("\\\$ROOT").findAll(script).count()
        val quoted = Regex("\"\\\$ROOT").findAll(script).count()

        // root 是宿主路径，可能含空格；漏一个引号就是"chroot 到一半的路径"。
        assertTrue("脚本里一个 \$ROOT 引用都没有，断言失去意义：\n$script", occurrences > 0)
        assertEquals("有没加引号的 \$ROOT 引用：\n$script", occurrences, quoted)
    }

    @Test
    fun scriptWritesTheInventoryOverlayWithTheBridgeInserted() {
        val script = script()

        assertTrue(
            "覆盖层不是用 heredoc 写的：\n$script",
            script.contains("cat > \"\$ROOT/opt/dsh/heta-inventory-overlay.patch.yml\" <<'HETA_OVERLAY'"),
        )
        // acp 必须关掉：它占着 stdio，不关探针永远等不到清单。
        assertTrue("覆盖层里没有关掉 acp：\n$script", script.contains("- id: acp\n  disabled: true\n"))
        // 桥用**相对名**挂进来（dsh 按覆盖层所在目录解析成 file://），所以桥和覆盖层同目录。
        assertTrue(
            "桥的 insert 行不对：\n$script",
            script.contains("    - id: heta-inventory-bridge\n      name: ./heta-inventory-bridge.mjs\n"),
        )
        // heredoc 必须干净收尾：覆盖层那段自带换行，分隔符才落在行首。
        // 桥之后还接着预设平面，所以"桥那一行紧跟着分隔符"不再成立 —— 判据是**分隔符自己
        // 落在行首**（前面是换行，后面也是换行），而不是它前面恰好是桥。
        assertTrue(
            "heredoc 分隔符没落在行首：\n$script",
            script.contains("\nHETA_OVERLAY\n"),
        )
        val overlayText = script.substringAfter("<<'HETA_OVERLAY'\n").substringBefore("\nHETA_OVERLAY\n")
        assertTrue("覆盖层里桥那一行不对：\n$overlayText", overlayText.contains("      name: ./heta-inventory-bridge.mjs\n"))
    }

    /**
     * 预设平面真的进了覆盖层，而且是官方那几段。
     *
     * 这几条断言的价值在 CI：`test-dsh-live-inventory.sh` 跑的就是这份导出的脚本，所以这里
     * 钉住的形状一旦漂移（少一条 Host 行、注册表默认值写成不存在的 id、四份声明少一份），
     * CI 那次的 dsh 会直接报出"预设挂不起来"，而不是悄悄退化成另一种行为。
     */
    @Test
    fun presetPlaneCarriesTheHostRowRegistryAndEveryShippedPreset() {
        val overlay = DshInventoryProbe.probeOverlay(presetPlane())

        // 官方 Web 那条 Host 行：少了它 standard/ptc/cordis 里的 tool-subagent 行挂载即抛错，
        // 注册表一条失败就整体拒绝 —— 表现是"预设列出来了、一行都没挂上"。
        assertTrue(
            "覆盖层里没有 subagent-model-selection-settings 那条 Host 行：\n$overlay",
            overlay.contains(
                "    - id: subagent-model-selection-settings\n" +
                    "      name: '@deepseek-ai/dsh-tool-subagent/model-selection-settings'\n",
            ),
        )
        // Heta 自己那一跳。
        assertTrue(
            "覆盖层里没有 join 插件那一行：\n$overlay",
            overlay.contains("    - id: heta-preset-join\n      name: ./heta-preset-join.mjs\n"),
        )
        // 注册表：默认值必须是四份声明里真有的那个。
        assertTrue(
            "注册表行不对：\n$overlay",
            overlay.contains(
                "- insert:\n    - id: agent-preset-registry\n" +
                    "      name: '@deepseek-ai/dsh-agent-preset-registry'\n" +
                    "      config:\n        default: standard\n",
            ),
        )
        // 四份官方声明，一份都不能少。
        DshPresetPlane.PRESET_IDS.forEach { id ->
            assertTrue(
                "少了 $id 的声明：\n$overlay",
                overlay.contains("    - id: preset-$id\n      name: '@deepseek-ai/dsh-agent-preset'\n"),
            )
        }
        // 部署人格必须进了预设的 persona 行（否则模型看不到身份句与技能库索引）。
        assertTrue(
            "部署人格没注入 standard 的 persona 行：\n$overlay",
            overlay.contains("              HETA-PROBE-IDENTITY\n"),
        )
        // minimal 是 `complete: true` 的预设：官方语义就是"只用那段前缀当系统提示词"，
        // 所以它必须**没有**被注入。
        assertFalse(
            "complete 的预设不该被注入人格：\n$overlay",
            overlay.substringAfter("preset-minimal").substringBefore("preset-cordis")
                .contains("HETA-PROBE-IDENTITY"),
        )
        // agent 平面让位：24 行，且每一行都是 disabled: true。
        val managed = DshPresetPlane.managedRowIds { path -> asset(path) }
        // 官方 dsh-web-app 那段是 24 行；第 25 行是 Heta 自己的补充（`pwsh-sandbox`：Android 上
        // 没有 PowerShell，留着一个永远"启动失败"的插件没有意义，见 plane-disable.patch.yml 末尾）。
        assertEquals("托管的行数不对（官方 24 + Heta 补充 1）", 25, managed.size)
        assertTrue("tool-bash 不在托管列表里", "tool-bash" in managed)
        assertTrue("tool-web 不在托管列表里", "tool-web" in managed)
        managed.forEach { id ->
            assertTrue(
                "$id 在平面里不是 disabled: true：\n$overlay",
                overlay.contains("- id: $id\n  disabled: true\n"),
            )
        }
    }

    @Test
    fun scriptLeavesNoGenerationPlaceholdersBehind() {
        // 占位符漏一个，落盘的就是一句语法错误（或一个指向字面 "@HETA_PATH@" 的路径），
        // 而那种错在 CI 里长成"dsh 没起来"，离原因很远。
        assertFalse("还有没被替换的生成期占位符：\n${script()}", script().contains("@HETA_"))
    }

    @Test
    fun overlayEndsWithANewlineSoTheHeredocTerminatorLandsOnItsOwnLine() {
        val overlay = DshInventoryProbe.probeOverlay()

        assertTrue("覆盖层结尾没有换行：\n$overlay", overlay.endsWith("\n"))
        assertTrue("覆盖层少了 acp 那段：\n$overlay", overlay.startsWith("- id: acp\n  disabled: true\n- insert:\n"))
    }

    /**
     * 把探针脚本导出给 CI（`scripts/test-dsh-live-inventory.sh`）。
     *
     * 导出的脚本里 root 是**临时目录**，但脚本的参数化让这不成问题：CI 传自己解包出来的
     * 那个 root 进去（`bash app/build/dsh-probe.sh <root>`）。想让 CI 跑的和 App 跑的是同一份
     * 文本，就不能让 CI 自己拼命令 —— 拼就会漏掉真实路径。
     */
    @Test
    fun writesTheProbeScriptForTheLiveInventoryCiRun() {
        val file = File("build/dsh-probe.sh")
        file.parentFile?.mkdirs()
        file.writeText(script())
        file.setExecutable(true)

        assertTrue("导出的探针脚本没写出来：${file.absolutePath}", file.length() > 0)
        assertTrue("导出的脚本里没有 exec chroot", file.readText().contains("exec chroot"))
    }

    /** 覆盖层的期望内容单独导出一份，便于 CI 对照脚本实际写到磁盘上的那份。 */
    @Test
    fun writesTheExpectedOverlayBesideTheProbeScript() {
        val file = File("build/dsh-probe-overlay.patch.yml")
        file.parentFile?.mkdirs()
        file.writeText(DshInventoryProbe.probeOverlay(presetPlane()))

        assertTrue("导出的覆盖层没写出来：${file.absolutePath}", file.length() > 0)
    }
}
