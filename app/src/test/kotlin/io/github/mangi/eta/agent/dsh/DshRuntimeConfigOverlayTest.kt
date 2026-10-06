package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.agent.model.AgentIdentity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.yaml.snakeyaml.Yaml

/**
 * 给 dsh 的 `--patch` 覆盖层写了什么。
 *
 * 回归背景：dsh 的 loader 对 `- id: X` / `config:` 是**整块替换**，不是按键合并。
 * 覆盖层里只写 `personaSuffix` 会把 dsh ACP profile 自带的 `personaPrefix`
 * （"You are a coding agent powered by the {{model}} model."）静默抹掉——
 * `dsh --profile acp --dump-config` 里那一行会直接消失，模型再也看不到自己是哪个模型。
 *
 * 所以这里把「两个键都得在」钉死：以后谁再往这个覆盖层里加插件配置，少写键就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshRuntimeConfigOverlayTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * 资产读成文本：单测的工作目录是 `app/`，与生产 `Context.assets` 读的是**同一个文件**。
     *
     * 于是导出的 `build/dsh-e2e-overlay.patch.yml` 里就带着真正的预设平面，端到端冒烟跑的
     * 就是"agent 平面搬进预设"之后的那个世界 —— 而不是一个没有预设的旧世界。
     */
    private fun asset(path: String): String = File("src/main/assets", path).readText()

    private fun config(
        skillsDirectory: String = "",
        model: String = "global:deepseek-v4.1-flash",
    ): DshRuntimeConfig {
        val rootfs = temporaryFolder.newFolder("dsh-runtime")
        return DshRuntimeConfig(
            rootfsPath = rootfs.absolutePath,
            providerRoute = "deepseek-official",
            model = model,
            apiKey = "sk-test",
            baseUrl = "https://example.invalid",
            skillsDirectory = skillsDirectory,
            presetPlane = DshPresetPlane.overlay(
                { path -> asset(path) },
                DshPresetPlane.DEFAULT_PRESET,
                // 用真实那套生成逻辑（身份句 + 技能库索引 + 工作目录）：端到端要验的就是它到底
                // 有没有到模型手里（预设的 persona 行会遮蔽部署级那一行）。
                listOf(
                    AgentIdentity.ROLE_LINE,
                    "",
                    "Your working directory is {{cwd}}.",
                    // 这一行得带上「技能库」三个字：端到端冒烟就是用它在模型收到的 system 里
                    // 找技能库索引（真跑时那几行由 DshRuntimeConfig.skillIndex() 生成）。
                    "技能库：HETA-PROBE-SKILLS",
                ),
            ),
            presetJoinPlugin = asset(DshPresetPlane.JOIN_ASSET),
            // 状态出口插件也要写进 rootfs：不然端到端里那条 insert 行指向一个不存在的文件，
            // 就永远验不到「会话进程写清单」这条路。
            statusPlugin = asset(DshPresetPlane.STATUS_ASSET),
            usagePlugin = asset(DshPresetPlane.USAGE_ASSET),
        )
    }

    private fun overlayOf(config: DshRuntimeConfig): String {
        // command() 会顺带把覆盖层写到 rootfs 里，返回的是 su 命令行；这里只关心副作用。
        config.command()
        return File(config.rootfsPath, "opt/dsh/heta-run-overlay.patch.yml").readText()
    }

    @Test
    fun overlayKeepsThePersonaPrefixAlongsideItsOwnSuffix() {
        val overlay = overlayOf(config())

        assertTrue(
            "personaPrefix 被整块替换吃掉了：\n$overlay",
            overlay.contains("personaPrefix: \"You are a coding agent powered by the {{model}} model.\""),
        )
        assertTrue("personaSuffix 没了：\n$overlay", overlay.contains("personaSuffix: |"))
        assertTrue(
            "身份没进 suffix：\n$overlay",
            overlay.contains("DeepSeek Harness 编码助手"),
        )
        assertTrue("工作目录一行应该还在后缀里：\n$overlay", overlay.contains("Your working directory is {{cwd}}."))
    }

    @Test
    fun overlayCarriesTheDsh020ModelDefaultsInsteadOfTheOldCatalog() {
        val overlay = overlayOf(config())

        assertTrue("flash 的缓存友好更新语义丢了：\n$overlay", overlay.contains("systemPromptUpdate: in-history"))
        assertTrue("flash 的工具增量更新语义丢了：\n$overlay", overlay.contains("toolUpdate: addition-only"))
        assertTrue("v4-pro 的说明丢了：\n$overlay", overlay.contains("Stronger agentic coding, knowledge, and difficult reasoning"))
        assertFalse("旧的 v4-flash 仍在覆盖目录里：\n$overlay", overlay.contains("id: \"deepseek-v4-flash\"\n"))
        assertFalse("已经下线的 vision-exp 模型仍在覆盖目录里：\n$overlay", overlay.contains("deepseek-v4-flash-vision-exp"))
    }

    @Test
    fun overlayStillPinsTheModelAndTheRoute() {
        val overlay = overlayOf(config())

        assertTrue(overlay.contains("- id: acp\n"))
        assertTrue(overlay.contains("    provider: \"deepseek-official\"\n"))
        assertTrue(overlay.contains("    model: \"global:deepseek-v4.1-flash\"\n"))
    }

    @Test
    fun commandUsesExposeInternalsInsteadOfTheAndroidNativeAddon() {
        val command = config().command().joinToString(" ")

        assertTrue("Node 必须启用内部模块旁路：$command", command.contains("--expose-internals"))
        assertTrue("dsh 入口仍在 Node 参数之后：$command", command.contains("/opt/dsh/lib/bin.js"))
    }

    @Test
    fun overlayIndexesTheSkillsDirectoryIntoTheSuffix() {
        val skills = temporaryFolder.newFolder("skills")
        File(skills, "demo-skill").apply { mkdirs() }
        File(skills, "demo-skill/SKILL.md").writeText(
            """
            ---
            name: demo-skill
            description: 一句话说明这个技能干什么
            ---

            正文。
            """.trimIndent(),
        )

        val overlay = overlayOf(config(skillsDirectory = skills.absolutePath))

        assertTrue("技能索引没进后缀：\n$overlay", overlay.contains("- demo-skill：一句话说明这个技能干什么"))
    }

    @Test
    fun overlaySaysSoWhenTheSkillsDirectoryIsEmpty() {
        val overlay = overlayOf(config())

        assertTrue("空技能库也要有明确文案：\n$overlay", overlay.contains("当前技能库为空。"))
    }

    /**
     * 用真 YAML 解析器读覆盖层，返回 `llm-deepseek.config.models` 的条目。
     *
     * 回归背景：`DshBuiltinModelCatalog.YAML` 是 `trimIndent()` 的产物、**结尾没有换行**，
     * 而它后面无论是下一个模型条目还是 `- id: acp`，都会被 `append` 粘到
     * `contextWindow: 1000000` 那一行上：
     *
     *     contextWindow: 1000000      - id: "global:deepseek-v4.1-flash"
     *
     * dsh 用 js-yaml 读这份覆盖层，这一步直接抛
     * `YAMLException: bad indentation of a mapping entry`，进程在 `initialize` 回包前退出，
     * Heta 侧只看到 `protocol-eof`。以前的 `contains(...)` 断言对坏 YAML 完全免疫，
     * 所以这里必须真解析一遍。
     */
    /**
     * 把 `!!js <表达式>` 折成一个普通标量再交给 SnakeYAML。
     *
     * 为什么：dsh 的 YAML 方言里带 `!!js`（官方那四份预设声明里就有，例如
     * `disabled: !!js process.platform === 'win32'`），dsh 自己用 js-yaml 配自定义 schema 解析它；
     * 而 SnakeYAML 2.x 默认拒绝未知的全局 tag（`Global tag is not allowed: tag:yaml.org,2002:js`），
     * 就算用 TagInspector 放行这个 tag，还得再补一个构造器，否则它找不到构造器照样抛。所以这里
     * 的"解析失败"是**解析器的策略**，不是覆盖层真的坏了 —— 真正证明 dsh 读得动的是
     * `scripts/test-dsh-e2e.sh`：真运行时、真 js-yaml，整份覆盖层都得过。
     *
     * 折叠只替换 `!!js` 之后那一段标量，映射 / 序列结构原样保留：覆盖层该有的形状仍然会被
     * SnakeYAML 检出来。
     */
    private fun asSnakeYamlUnderstands(overlay: String): String =
        overlay.replace(Regex("!!js [^\\n]*"), "true")

    private fun modelsOf(overlay: String): List<Map<*, *>> {
        val parsed: Any? = Yaml().load<Any>(asSnakeYamlUnderstands(overlay))
        assertTrue("覆盖层没解析成列表：\n$overlay", parsed is List<*>)
        val entries = (parsed as List<*>).filterIsInstance<Map<*, *>>()
        val llm = entries.firstOrNull { it["id"] == "llm-deepseek" }
        assertNotNull("覆盖层里没有 llm-deepseek：\n$overlay", llm)
        val models = (llm?.get("config") as? Map<*, *>)?.get("models")
        assertTrue("llm-deepseek.config.models 不是列表：\n$overlay", models is List<*>)
        return (models as List<*>).filterIsInstance<Map<*, *>>()
    }

    @Test
    fun overlayParsesAsYamlWithAnExtraModel() {
        val overlay = overlayOf(config())
        val models = modelsOf(overlay)

        assertEquals(
            "models 条目数 = 内置目录 + 自定义模型：\n$overlay",
            DshBuiltinModelCatalog.IDS.size + 1,
            models.size,
        )
        assertEquals(
            "自定义模型没进目录或顺序变了：\n$overlay",
            DshBuiltinModelCatalog.IDS + "global:deepseek-v4.1-flash",
            models.map { it["id"] },
        )
    }

    @Test
    fun extraModelCarriesTheSameUpdateSemanticsAsTheBuiltinOne() {
        val overlay = overlayOf(config())
        val extra = modelsOf(overlay).first { it["id"] == "global:deepseek-v4.1-flash" }

        // dsh 只在"有值"时才透传这两个键：省略 = 不声明，续接的旧会话拿不到 prompt/工具更新。
        assertEquals("systemPromptUpdate 丢了：\n$overlay", "in-history", extra["systemPromptUpdate"])
        assertEquals("toolUpdate 丢了：\n$overlay", "addition-only", extra["toolUpdate"])
    }

    @Test
    fun startupScriptPrunesAgedDshSessions() {
        val config = config()
        val command = config.command().joinToString(" ")

        // dsh 自己的会话文件没人回收，只能在启动时按年龄清。
        assertTrue(
            "启动脚本没有清理 dsh 会话目录：$command",
            command.contains("${config.rootfsPath}/root/.dsh/sessions"),
        )
        assertTrue("清理没带年龄条件：$command", command.contains("-mtime +14 -delete"))
        // 投影缓存比会话本体大一个数量级，漏了它等于只清了小头。
        assertTrue(
            "投影缓存没清：$command",
            command.contains("${config.rootfsPath}/root/.dsh/storages/session_projcache"),
        )
        // 只删文件会留下一树空壳。
        assertTrue("没收回空目录：$command", command.contains("-type d -empty -delete"))
    }

    /**
     * 预设平面真的进了覆盖层，而且顺序是对的。
     *
     * 顺序不是审美问题：官方那条 Host 行必须**排在四份预设声明之前** —— 预设声明一注册就会
     * 立刻激活，而 standard/ptc/cordis 里的 `tool-subagent` 行带 `modelSelectionSettings: true`，
     * 挂载时马上 `ctx.get("subagentModelSelection")`；那一行还没激活就抛错，注册表审计一条失败
     * 即整体拒绝（`dsh-agent-preset-registry/lib/index.js:272-273`），四个预设定全部退回"声明文件"
     * 状态 —— 表现就是"列出来了、一行都没挂上"。
     */
    @Test
    fun overlayCarriesThePresetPlaneInTheOrderTheRegistryNeeds() {
        val overlay = overlayOf(config())

        val host = overlay.indexOf("subagent-model-selection-settings")
        val registry = overlay.indexOf("agent-preset-registry")
        val firstDeclaration = overlay.indexOf("preset-standard")
        val join = overlay.indexOf("heta-preset-join")
        val firstDisabled = overlay.indexOf("- id: tool-bash\n  disabled: true")

        assertTrue("没有 Host 行：\n$overlay", host >= 0)
        assertTrue("没有注册表行：\n$overlay", registry >= 0)
        assertTrue("没有预设声明：\n$overlay", firstDeclaration >= 0)
        assertTrue("没有 join 插件行：\n$overlay", join >= 0)
        assertTrue("Host 行必须排在预设声明之前：host=$host decl=$firstDeclaration", host < firstDeclaration)
        assertTrue("注册表必须排在预设声明之前：reg=$registry decl=$firstDeclaration", registry < firstDeclaration)
        // agent 平面让位排在声明之后（官方也是这个顺序：先让每份预设拿到自己的行，再把根上的关掉）。
        assertTrue("让位块没在末尾：$firstDisabled vs $firstDeclaration", firstDisabled > firstDeclaration)
        // 默认预设就是选中的那个（这里没选过，所以是官方默认值）。
        assertTrue(
            "注册表的 default 不是官方默认值：\n$overlay",
            overlay.contains("        default: ${DshPresetPlane.DEFAULT_PRESET}\n"),
        )
        // 身份句真的被塞进了预设的 persona 行（不是留在部署级那行等它被遮蔽）。
        assertTrue(
            "身份句没进预设 persona 行：\n$overlay",
            overlay.contains("              ${AgentIdentity.ROLE_LINE}\n"),
        )
        // 四份声明都在。
        DshPresetPlane.PRESET_IDS.forEach { id ->
            assertTrue("少了 $id：\n$overlay", overlay.contains("    - id: preset-$id\n"))
        }
    }

    /** join 插件真的被写进了 root（覆盖层里用相对名引用它，两者必须同目录）。 */
    @Test
    fun joinPluginLandsBesideTheOverlay() {
        val config = config()
        config.command()

        val file = File(config.rootfsPath, "opt/dsh/heta-preset-join.mjs")
        assertTrue("join 插件没写出来：${file.absolutePath}", file.isFile)
        val text = file.readText()
        assertTrue("join 插件里没有 agent/created：\n$text", text.contains("agent/created"))
        assertTrue("join 插件里没有 mount：\n$text", text.contains("presets.mount("))
    }

    /**
     * 把当前实现生成的 overlay 写到 `build/dsh-e2e-overlay.patch.yml`，给端到端冒烟测试用
     * （`scripts/test-dsh-e2e.sh`）：它拿这份真 overlay 去启动真运行时。所以 overlay 一旦又被
     * 拼坏（`--patch` 坏 YAML 那次），冒烟测试会直接红 —— 这正是那条教训的兜底。
     */
    @Test
    fun writesTheGeneratedOverlayForTheEndToEndSmokeTest() {
        val file = java.io.File("build/dsh-e2e-overlay.patch.yml")
        file.parentFile?.mkdirs()
        file.writeText(overlayOf(config()))

        assertTrue("overlay 没写出来：${file.absolutePath}", file.length() > 0)
    }

    /**
     * 把一份**带模式意图**的覆盖层写到 `build/dsh-e2e-overlay-modes.patch.yml`，给端到端
     * 冒烟的第 ⑩ 步用：那一步拿它去启动真运行时，然后断言 ① 模型收到的 system 里真有
     * 计划模式那一段（说明 `/plan` 真的被执行了）② 目标模式真的建起了目标，而且自动续轮
     * 在 ACP 这条路上跑得通（假模型会收到 `<goal_round>`）。
     *
     * 只在这一份里出现的东西：`goal` 行的自动续轮上限被压到 1。假模型永远不会宣告目标
     * 完成，不压上限的话冒烟会一轮接一轮跑到默认的 256 轮。目标原文里故意带引号和冒号：
     * 拼出来的 YAML 必须仍然能被真 js-yaml 读进去（同一个坑踩过两次）。
     */
    @Test
    fun writesTheModeOverlayForTheEndToEndSmokeTest() {
        val rootfs = temporaryFolder.newFolder("dsh-runtime-modes")
        val config = DshRuntimeConfig(
            rootfsPath = rootfs.absolutePath,
            providerRoute = "deepseek-official",
            model = "global:deepseek-v4.1-flash",
            apiKey = "sk-test",
            baseUrl = "https://example.invalid",
            presetPlane = DshPresetPlane.overlay(
                { path -> asset(path) },
                DshPresetPlane.DEFAULT_PRESET,
                listOf(
                    AgentIdentity.ROLE_LINE,
                    "",
                    "Your working directory is {{cwd}}.",
                    "技能库：HETA-PROBE-SKILLS",
                ),
                DshSessionModes(plan = true, goal = E2E_GOAL),
            ) + E2E_GOAL_ROUND_CAP,
            presetJoinPlugin = asset(DshPresetPlane.JOIN_ASSET),
            // 状态出口插件也要写进 rootfs：不然端到端里那条 insert 行指向一个不存在的文件，
            // 就永远验不到「会话进程写清单」这条路。
            statusPlugin = asset(DshPresetPlane.STATUS_ASSET),
            usagePlugin = asset(DshPresetPlane.USAGE_ASSET),
        )
        val file = java.io.File("build/dsh-e2e-overlay-modes.patch.yml")
        file.parentFile?.mkdirs()
        file.writeText(overlayOf(config))

        val overlay = file.readText()
        assertTrue("模式覆盖层里没有 plan: true：\n$overlay", overlay.contains("plan: true"))
        // 只找标记串：目标原文里有引号，进 YAML 时被 JSON 转义成 `\"` —— "整句一字不差"
        // 由 `DshPluginInventoryTest.modeIntentLandsOnTheJoinRowWithoutDisturbingThePlane`
        // 把 YAML 真解析一遍来钉（那才是 dsh 读到的形态）。
        assertTrue("模式覆盖层里没有目标标记串：\n$overlay", overlay.contains("HETA-E2E-GOAL"))
    }

    /**
     * 把**真启动脚本**写到 `build/dsh-startup-script.sh`，给端到端冒烟测试用
     * （`scripts/test-dsh-e2e.sh`）。
     *
     * 端到端以前自己手写 `export DEEPSEEK_*`，于是「凭据写成 0600 文件 → `set -a; . file;
     * set +a; rm -f file`」这条真路径一次都没被跑过 —— 只有 `contains` 字符串断言，正是坏
     * YAML 那次的病根。现在端到端跑的就是这里导出的脚本：`. file` 没生效 → 没有 baseUrl →
     * 打到真网关 → 冒烟直接红；跑完凭据文件还在 → 冒烟也红。
     *
     * 第一行注释带着 rootfs 路径，供端到端 sed 成本次自己的临时目录。
     */
    @Test
    fun writesTheRealStartupScriptForTheEndToEndSmokeTest() {
        val config = config()
        val script = config.command().last()
        val file = java.io.File("build/dsh-startup-script.sh")
        file.parentFile?.mkdirs()
        file.writeText("# dsh-e2e-rootfs=" + config.rootfsPath + "\n" + script)
        file.setExecutable(true)

        assertTrue("脚本里应当有凭据文件的 source：$script", script.contains("set -a; . "))
        assertTrue("导出的启动脚本没写出来：${file.absolutePath}", file.length() > 0)
    }

    @Test
    fun commandKeepsCredentialsOutOfArgv() {
        val config = config()
        val command = config.command().joinToString(" ")

        // `su -c <script>` 的整段脚本就是 argv，凭据写进去等于给 `ps` 看。
        assertFalse("API Key 出现在启动命令里：$command", command.contains("sk-test"))
        assertFalse("网关地址出现在启动命令里：$command", command.contains("example.invalid"))
        // source 发生在 exec chroot 之前，所以这里必须是宿主路径。
        assertTrue(
            "凭据文件没按宿主路径 source：$command",
            command.contains("${config.rootfsPath}/opt/dsh/heta-run-env.sh"),
        )
        // set -a 才让 source 进来的变量被导出；rm -f 让凭据不留下文件副本。
        assertTrue("source 没配套 set -a：$command", command.contains("set -a;"))
        assertTrue("凭据文件用完没删：$command", command.contains("rm -f"))
    }

    @Test
    fun overlayParsesAsYamlWhenTheModelIsBuiltIn() {
        // 内置模型时不追加条目，但目录常量后面紧跟着 `- id: acp`，同样会被粘坏。
        val overlay = overlayOf(config(model = "deepseek-flash"))
        val models = modelsOf(overlay)

        assertEquals("models 条目数 = 内置目录：\n$overlay", DshBuiltinModelCatalog.IDS.size, models.size)
        assertEquals("内置目录被改动了：\n$overlay", DshBuiltinModelCatalog.IDS, models.map { it["id"] })
    }

    private companion object {
        /** 端到端第 ⑩/⑪ 步用的目标原文（两边必须逐字一致：脚本拿它去请求里找）。 */
        const val E2E_GOAL = "HETA-E2E-GOAL：把 README 的\"用法\"一节补上"

        /** 端到端专用：把目标模式的自动续轮上限压到 1（假模型永远不会宣告完成）。 */
        val E2E_GOAL_ROUND_CAP = """
            # 端到端专用：自动续轮上限 1（否则假模型会一直干到默认的 256 轮）。
            - id: goal
              config:
                defaultMaxGoalRounds: 1

        """.trimIndent()
    }
}
