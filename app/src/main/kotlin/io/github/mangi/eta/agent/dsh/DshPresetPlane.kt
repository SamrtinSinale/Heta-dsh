package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 官方 agent 预设要跑起来，Heta 必须补的那几段补丁层内容。
 *
 * 背景（都对 0.2.0-rc.2 的原文核过）：官方那套预设不是"一份配置"，而是一种**架构** ——
 * `@deepseek-ai/dsh-web-app` 的 `cordis.patch.yml` 里那段 "the agent plane moves behind agent
 * presets" 把 24 个 agent 平面的行从根上 `disabled: true`，改由每个会话挂载一个预设来提供；
 * 预设本身是四份静态 YAML（`presets/{standard,ptc,minimal,cordis}.patch.yml`），注册表是
 * `agent-preset-registry` 那一行。
 *
 * Heta 走的是 `dsh-base` + `dsh-acp-app`：官方这两份 bundle 对 `agentPresets` **零命中** ——
 * 既没有那段 disabled，也没有注册表，更没有"会话 → 预设"的那一跳。所以这几样都得自己补：
 *
 *  1. `plane.patch.yml`：官方 Web 那条 Host 行（`dsh-tool-subagent/model-selection-settings`，
 *     它只是**已装包的一个子路径**，不是新包）+ 我们那一跳的插件行；
 *  2. [registryRow]：注册表行。`config.default` 就是**当前选中的预设** —— 页面上"设为新任务
 *     默认"改的就是这个值，下一轮 run 重新生成覆盖层时生效；
 *  3. 四份官方预设声明：逐字节照抄，一个字都不改（改过就不是官方的预设置了）；
 *  4. `plane-disable.patch.yml`：那 24 行 disabled，必须与官方同源，否则会出现"根上一套工具
 *     ＋ 预设里一套工具"的两份。
 *
 * 为什么走 `--patch` 覆盖层、而不是 profile 补丁层：覆盖层**每次 run 重新生成**、优先级最高，
 * 而用户在扩展页手动开关写的是 profile 层 —— 覆盖不过来，这正是我们要的（预设托管的行不该被
 * 单个开关改掉）。另一个好处是"换预设"只要换一个字符串，不必做 YAML 行编辑。
 *
 * 资产放在 `assets/heta-presets/` 下，内容由 `_dsh_store/gen_preset_assets.py` 式的一次性脚本
 * 从官方 npm 包直接抄出来（见每个文件顶部的出处注释）；Heta 侧只负责拼装与选择。
 */
internal object DshPresetPlane {
    /** 四份官方预设的 id，顺序照官方声明里的 `order`。 */
    val PRESET_IDS = listOf("standard", "ptc", "minimal", "cordis")

    /** 部署默认值：官方 `dsh-web-app/cordis.patch.yml:565` 就是 `default: standard`。 */
    const val DEFAULT_PRESET = "standard"

    /** 创造模式：官方四份声明里的 `cordis`（id 就是它，客户端文案叫「创造模式」）。 */
    const val CREATOR_PRESET = "cordis"

    /**
     * 哪几份官方预设提供计划模式（`/plan` + `exit_plan_mode`）与目标模式（`/goal`）。
     *
     * 事实来源是四份声明本身（standard / ptc / cordis 里都有 `command-goal`、`tool-goal`
     * 和 planning 组里的 `plan-mode`；minimal 只有 persona + 持久 shell，刻意什么都没有）。
     * 界面上不该给一个点了没反应的入口，所以这份名单要与资产一致 —— 有单测钉着
     *（[DshPluginInventoryTest]：名单里的必须有那几行，名单外的必须没有）。
     */
    val MODE_PRESET_IDS = setOf("standard", "ptc", "cordis")

    /** join 插件：官方把"会话 → 预设"放在 Web 浏览器半边，ACP 这条路上只能我们自己补。 */
    const val JOIN_ASSET = "heta-preset-join.mjs"

    private const val ASSET_DIR = "heta-presets"

    /**
     * 把 `assets/<path>` 读成文本。
     *
     * 生产走 `Context.assets`，单测走仓库里的同名文件 —— 同一份内容，两条路径不可能漂移
     * （同 `DshInventoryProbe` 从 assets 读桥，而不是在 Kotlin 里再写一份字面量）。
     */
    fun reader(context: Context): (String) -> String = { path ->
        context.assets.open(path).use { input -> input.readBytes().decodeToString() }
    }

    /**
     * 交给 dsh 的那一整段覆盖层：Host 行 → 注册表行 → 四份声明 → 24 行 disabled。
     *
     * [suffixLines] 是 Heta 的**部署人格后缀**（身份句 + 技能库索引 + 工作目录），会被塞进每份
     * 预设的 `persona` 行 —— 为什么必须这么做见 [injectDeploymentSuffix]。空列表 = 不注入
     *（只有单测会用空列表）。
     */
    fun overlay(
        read: (String) -> String,
        selected: String,
        suffixLines: List<String> = emptyList(),
        modes: DshSessionModes = DshSessionModes(),
    ): String = buildString {
        append(withModes(read(asset("plane.patch.yml")), modes))
        append('\n')
        append(registryRow(selected))
        PRESET_IDS.forEach { id ->
            val declaration = read(asset("$id.patch.yml"))
            append(
                if (suffixLines.isEmpty()) {
                    declaration
                } else {
                    injectDeploymentSuffix(declaration, suffixLines)
                }
            )
            append('\n')
        }
        append(read(asset("plane-disable.patch.yml")))
    }

    /**
     * 把 Heta 的部署人格后缀写进预设的 `persona` 行。
     *
     * 为什么必须这么做（实测出来的，不是推理）：`dsh-persona` 的 `suffix` 字段**遮蔽部署级的
     * `deployment:persona-suffix`**。官方 README 的原话是 "omitted or empty text shadows the
     * global suffix away"；而 Heta 的身份句与技能库索引一直挂在部署级那一行
     *（`DshRuntimeConfig.writeProfileOverlay()` 里的 `system-prompt`）—— 预设一开，它就再也到不了
     * 模型。端到端实测过：打开预设之后模型收到的 system 里既没有「DeepSeek Harness 编码助手」，
     * 也没有技能库索引（技能于是永远不会被用上）。
     *
     * 官方那四份声明里 standard / ptc / cordis 的 persona 行都写着
     * `suffix: Your working directory is {{cwd}}.`，那一行正是"部署后缀"在同一位置的替身；
     * 把它换成一个多行 `|-` 块，就是 dsh 设计里"预设自带部署人格"的用法
     *（`dsh-persona` 的包描述：*"Composition-authored deployment persona section"*）。
     *
     * `complete: true` 的预设（minimal）**原样返回**：它的语义就是"只用这段前缀当系统提示词、
     * 不带身份 / 后缀 / 工具指引"，官方那句说明也写了它不加载 Skills —— 往里塞东西既是徒劳
     *（README：no identity, suffix, tool guidance, or listener can append prompt text），
     * 也违背那份预设的本意。
     *
     * 锚点找不到时**不改**（只 warn）：那时模型会丢掉身份与技能库，而这件事有单测钉着
     *（每份声明要么被注入、要么是 complete），上游文案一变就在本地红。
     */
    fun injectDeploymentSuffix(declaration: String, suffixLines: List<String>): String {
        if (COMPLETE_PERSONA.containsMatchIn(declaration)) return declaration
        val match = DEPLOYMENT_SUFFIX_LINE.find(declaration) ?: run {
            Log.w(TAG, "预设声明里没有 persona 的 suffix 锚点，这一份不注入部署人格")
            return declaration
        }
        val indent = match.groupValues[1]
        val body = suffixLines.joinToString("\n") { line -> "$indent  $line".trimEnd() }
        return declaration.replaceRange(match.range, indent + "suffix: |-\n" + body)
    }

    private val COMPLETE_PERSONA = Regex("(?m)^\\s+complete: true\\s*$")
    private val DEPLOYMENT_SUFFIX_LINE =
        Regex("(?m)^(\\s*)suffix: Your working directory is \\{\\{cwd\\}\\}\\.\\s*$")

    /**
     * 注册表行。`default` 必须是 [PRESET_IDS] 里的一个：写成不存在的 id 时注册表会把
     * "默认预设"指向一个没有声明的名字，新会话 join 不上 —— 那时候是一行工具都没有。
     */
    fun registryRow(selected: String): String = buildString {
        append("# 预设选择注册表：`default` = 当前选中的预设，由 Heta 的扩展页写入。\n")
        append("- insert:\n")
        append("    - id: agent-preset-registry\n")
        append("      name: '@deepseek-ai/dsh-agent-preset-registry'\n")
        append("      config:\n")
        append("        default: ")
            .append(if (selected in PRESET_IDS) selected else DEFAULT_PRESET)
            .append('\n')
    }

    /**
     * 平面那几行（**不依赖任何文件**）。
     *
     * 为什么要有这个入口：平面是每轮 run 才写进 `opt/dsh/heta-run-overlay.patch.yml` 的，而
     * 「扩展」页读文件视图时那次 run 可能还没发生 —— 刚重装完运行时（`REVISION` 变了会整棵树
     * 重解包）、还没开始任何对话，那个覆盖层文件根本不存在。于是文件层里一行平面都没有，页面上
     * 把十行**正在跑**的插件标成"配置里还没有这一行"，用户点刷新也刷不出来（真机反馈：
     * "刷新是个空壳"）。平面内容是 Heta 自己生成的，直接当一层交给清单就是**同一份文本**，
     * 不存在与文件漂移的可能。
     *
     * 解析失败返回空列表（页面照旧能用，只是少这几行）——不抛异常给界面。
     */
    fun planeRows(
        read: (String) -> String,
        selected: String,
        modes: DshSessionModes = DshSessionModes(),
    ): List<DshPatchRow> =
        runCatching { DshPatchDocument.parse(overlay(read, selected, modes = modes)).rows }
            .getOrDefault(emptyList())

    /**
     * 把会话的模式意图写进 join 那一行的 config。
     *
     * 为什么挂在这一行上：模式在 dsh 里是**斜杠命令**的状态（`/plan`、`/goal`），而 ACP 没有
     * 命令通道 —— 只能由 Host 侧补那一次 `commands.execute()`。放这一行是因为它已经负责
     * "每个会话开一次"这件事，而且必须在**预设挂好之后**才执行（`/plan` / `/goal` 是预设里的
     * 行注册的）。
     *
     * [DshSessionModes.untouched] 时原样返回：普通会话的覆盖层一个字节都不变（单测与端到端
     * 都对着它断言）。
     */
    fun withModes(plane: String, modes: DshSessionModes): String {
        if (modes.untouched) return plane
        val match = JOIN_ROW_NAME_LINE.find(plane) ?: run {
            Log.w(TAG, "平面里找不到 join 那一行，这一轮的计划/目标模式不会生效")
            return plane
        }
        val indent = match.groupValues[1]
        val config = buildString {
            append('\n').append(indent).append("config:")
            modes.plan?.let { append('\n').append(indent).append("  plan: ").append(it) }
            modes.goal?.let { append('\n').append(indent).append("  goal: ").append(quote(it)) }
        }
        return plane.replaceRange(match.range, match.value + config)
    }

    /** join 那一行的 `name:` 整行（缩进要留着：config 必须与它同层）。 */
    private val JOIN_ROW_NAME_LINE = Regex("(?m)^(\\s*)name: \\./heta-preset-join\\.mjs\\s*$")

    /** YAML 里安全的双引号字符串：目标是一句用户自己写的话，换行、引号、反斜杠都可能出现。 */
    private fun quote(value: String): String = JSONObject.quote(value)

    /** join 插件的全文（写进 runtime root，覆盖层里用相对名 `./heta-preset-join.mjs` 引用它）。 */
    fun joinPlugin(read: (String) -> String): String = read(JOIN_ASSET)

    /**
     * 被预设接管的行 id。
     *
     * 覆盖层每一轮都会把这些行按回 `disabled: true`，所以页面上单独开关它们**不会有任何效果**
     * —— 与其让用户点了没反应，不如在那一行上把原因说清楚（见 `DshPluginInventory` 的
     * `readOnlyReason`）。
     */
    fun managedRowIds(read: (String) -> String): Set<String> =
        read(asset("plane-disable.patch.yml"))
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- id: ") }
            .map { it.removePrefix("- id: ").trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * 装配好的覆盖层正文；资产读不出来时返回空串。
     *
     * 空串 = 覆盖层里整段不写 = 运行时退回 `dsh-base` 原样（根上一套工具、没有预设），也就是
     * 今天的行为。**降级必须安全**：绝不能出现"注册表装上了、agent 平面却被关掉"的中间态 ——
     * 那种状态下每个会话一行工具都没有，比没有预设糟糕得多。所以这里只做整段成功/整段放弃。
     *
     * 探针与真启动共用这一个入口：两边拼出来的文本必须是同一份，否则探针报的清单不是真的。
     */
    fun overlayFor(
        context: Context,
        selected: String,
        suffixLines: List<String> = emptyList(),
        modes: DshSessionModes = DshSessionModes(),
    ): String =
        runCatching { overlay(reader(context), selected, suffixLines, modes) }.getOrElse { throwable ->
            Log.w(TAG, "preset plane 读不出来，这一轮不启用预设", throwable)
            ""
        }

    /** join 插件全文；读不出来返回空串（同 [overlayFor]，整段放弃）。 */
    fun joinPluginFor(context: Context): String =
        runCatching { joinPlugin(reader(context)) }.getOrElse { throwable ->
            Log.w(TAG, "preset join 插件读不出来", throwable)
            ""
        }

    /** 当前选中的预设；读不出资产时也返回官方默认值（页面照常显示四个选项）。 */
    fun selectedFor(context: Context): String = DshPresetSelection.selected(context)

    /** 被预设接管的行 id；读不出来返回空集合（页面上就不给"预设托管"的理由）。 */
    fun managedRowIdsFor(context: Context): Set<String> =
        runCatching { managedRowIds(reader(context)) }.getOrDefault(emptySet())

    private const val TAG = "DshPresetPlane"

    private fun asset(name: String) = "$ASSET_DIR/$name"
}
