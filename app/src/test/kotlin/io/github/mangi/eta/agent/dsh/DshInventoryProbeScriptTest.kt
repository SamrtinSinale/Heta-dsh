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

    private fun script(): String = DshInventoryProbe.probeScript(
        runtimeRoot = runtimeRoot,
        pathValue = DshRuntimeConfig.PATH_IN_ROOT,
        permissionMode = "danger-full-access",
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
        assertTrue(
            "heredoc 分隔符被粘在别的内容后面：\n$script",
            script.contains("      name: ./heta-inventory-bridge.mjs\nHETA_OVERLAY\n"),
        )
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
        file.writeText(DshInventoryProbe.probeOverlay())

        assertTrue("导出的覆盖层没写出来：${file.absolutePath}", file.length() > 0)
    }
}
