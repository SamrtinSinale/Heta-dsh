package io.github.mangi.eta.agent.dsh

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「扩展」页面要显示的**活清单**：dsh 进程内那份 Loader 条目的投影。
 *
 * 为什么需要第二份清单：现有 [DshPluginInventory] 读的是**补丁层文件**，它能回答"配置里写了
 * 什么"，回答不了"dsh 到底挂上了什么、跑成什么样"。官方客户端那一页显示的正是后者（配置状态 +
 * 运行状态 + 预设），数据来自 dsh 进程内的 `readPluginInventory`
 * （`@deepseek-ai/dsh-host-plugin-inventory`）。ACP 通道没有 remote 出口，所以那份数据只能
 * 由我们自己起一次 dsh、把桥插件挂进去打出来（见 [DshInventoryProbe]）。
 *
 * 字段名与那份官方投影逐字对应：
 *   · `entryId`    = `entry.id`（Loader 树里的 id，可能与补丁行的 id 不同——见
 *     [DshExtensionsStore.patchRowFor] 的说明）；
 *   · `moduleName` = `entry.options.name`；
 *   · `enabled`    = `!entry.disabled`（**配置状态**：配置里这一行是不是开着的）；
 *   · `fiberPhase` = `entry.fiber.state` 经映射表得到（**运行状态**），没挂上就是 null；
 *   · `patchId`    = `entry.options.id`（**补丁行 id**；官方 `listPlugins` 就是自己回 Loader 树
 *     里补上这一跳的）。开关写文件要的正是它，**不是** [entryId]：写文件按补丁行定位
 *     （[DshProfileStore.setPluginEnabled]），而 Loader 树里的 id 带 `include:` 前缀，两者不是
 *     一回事。桥没给这个键（`options.id` 缺失）时是 null —— 界面据此把这一行的开关禁掉并说明原因。
 */
internal data class DshLiveEntry(
    val entryId: String,
    val moduleName: String,
    val enabled: Boolean,
    val fiberPhase: String?,
    val patchId: String?,
    /** 官方给的本地化显示元数据（`pluginPackages`），没有就是 null —— 界面退回短包名。 */
    val title: DshLocalizedText? = null,
    val description: DshLocalizedText? = null,
)

/**
 * 预设（agent preset）里的一行。
 *
 * 行是**组合**里的行，不是 Loader 条目：它可能带 `condition` 表示"由这个表达式决定"，
 * 那时 `enabled` 是什么由配置决定而不是布尔 —— 所以 [enabled] 可以是 null，
 * 而"是不是条件行"单独用 [conditional] 表达，两件事不混在一个字段里。
 */
internal data class DshLivePresetRow(
    val entryId: String?,
    val moduleName: String,
    val enabled: Boolean?,
    val conditional: Boolean,
    val condition: String?,
    val fiberPhase: String?,
    /**
     * 补丁行 id，**只透传**：官方那份投影里预设行没有这个字段（`AgentPresetPluginRow` 只有
     * entryId / moduleName / enabled / condition / fiberPhase），官方那边预设组合行也不参与写文件
     *（见 `heta-inventory-bridge.mjs` 里 readPresets 的说明）。所以它通常是 null；留着这个字段
     * 是为了桥真带出来时不丢信息 —— 界面这一期不给预设行开关。
     */
    val patchId: String?,
    /** 同 [DshLiveEntry.title]：官方那份投影里预设行也带 `meta`。 */
    val title: DshLocalizedText? = null,
    val description: DshLocalizedText? = null,
)

/**
 * 官方 `PluginLocalizedMeta` 里的那两种写法：**字面串**，或 `locale → 文案` 的表
 *（`LocalizedText = string | { en: string; [locale: string]: string }`）。
 *
 * 为什么原样留着、不在解析时定死语言：解析在数据层，它不知道界面现在是什么语言；官方的
 * 做法也是把两种形态原样送到客户端、由客户端按当前语言解析（`typeof title === 'object' ?
 * resolveText(title) : moduleShortName(...)`）。
 */
internal sealed interface DshLocalizedText {
    data class Literal(val text: String) : DshLocalizedText
    data class Localized(val values: Map<String, String>) : DshLocalizedText

    /**
     * 按偏好顺序取第一条非空文案；一条都没有就是 null（调用方退回包名）。
     *
     * 字面串不看偏好 —— 它本来就没有语言标记。
     */
    fun resolve(preferred: List<String>): String? = when (this) {
        is Literal -> text.takeIf { it.isNotBlank() }
        is Localized -> preferred.firstNotNullOfOrNull { key ->
            values[key]?.takeIf { it.isNotBlank() }
        }
    }
}

/** 一个预设：名字、是不是默认、以及它组合了哪些行。 */
internal data class DshLivePreset(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    /**
     * 挂不起来的原因（官方 `AgentPresetComposition.broken`），挂得起来就是 null。
     *
     * **挂不起来时 [rows] 一定是空的**（官方那条字段的说明就是 "empty when the preset is
     * broken"）。所以界面不能只看"有没有行"：那会把坏预设显示成一个空预设，用户选它、然后
     * 什么都不会发生。有 [broken] 就必须把原因摆出来，并且**不许选它**。
     */
    val broken: String?,
    val rows: List<DshLivePresetRow>,
)

/**
 * 一次"读活清单"的结局。
 *
 * 刻意做成两个分支而不是"空清单 + 错误信息"：取不到活清单时页面要**退回文件视图**，
 * 那是一个不同的渲染路径，不是一个空列表。
 */
internal sealed interface DshLiveInventory {

    /**
     * 读到了。
     *
     * [presets] 为空**不是**常态了：资产里已经装上 `dsh-agent-preset-registry`，补丁层也有四份
     * 官方声明（见 [DshPresetPlane]），所以正常装机上这里会有四个预设。为空只可能出现在
     * "预设资产没装进去"或"注册表挂了"这两种情况下 —— 那时页面照旧只显示插件行。
     */
    data class Ready(val entries: List<DshLiveEntry>, val presets: List<DshLivePreset>) : DshLiveInventory {

        /**
         * 解析它的那段 JSON **原文**（不含 marker）。
         *
         * 为什么留着：App 要把活清单**落盘**缓存（进程级缓存活不过一次重启，而那正是"每次重启后
         * 第一次点开扩展页都要等 ~27 秒"的来源）。落盘写原文、读回来走同一个 [DshLiveInventoryCodec.parse]
         * —— 不用再写一个与解析器对称的序列化器，也就没有"两处漂移"这回事。
         *
         * 为什么是 body 属性而不是构造参数：它只是"这份清单从哪来"的附注，**不参与相等性**
         *（单测里 `Ready(entries, presets)` 的比较照旧只看内容）。
         */
        var raw: String = ""
    }

    /** 没读到。[reason] 是可读的原因，界面原样显示（必要时会被 [DshInventoryProbe] 附上 stderr 尾巴）。 */
    data class Failed(val reason: String) : DshLiveInventory
}

/**
 * 解析桥插件打出来的那一行 JSON。
 *
 * 为什么是"找 marker"而不是"整份 stdout 当 JSON"：dsh 自己会往 stdout/stderr 写日志，
 * 桥只在**一行**里带 [MARKER] 前缀。找不到、JSON 坏、桥报超时、entries 是空的 —— 一律
 * 变成 [DshLiveInventory.Failed]，**绝不抛异常给界面**：这个页面的底线是"探针出任何事都不许
 * 变成一整片空白"。
 */
internal object DshLiveInventoryCodec {

    /**
     * 桥的输出协议前缀；与 `heta-inventory-bridge.mjs` / `heta-status.mjs` 里的 MARKER 是同一个字符串。
     *
     * `internal` 而不是 `private`：[timestampOf] / [encode] 与探针、落盘缓存共用它 —— 三处各写一遍
     * 字符串，改一处就会有一处悄悄读不到。
     */
    internal const val MARKER = "HETA-INVENTORY-JSON:"

    private const val TIMED_OUT = "timedOut"
    private const val ERROR = "error"
    private const val ENTRIES = "entries"
    private const val PRESETS = "agentPresets"
    private const val ENTRY_ID = "entryId"
    private const val MODULE_NAME = "moduleName"
    private const val PATCH_ID = "patchId"
    private const val META = "meta"
    private const val TITLE = "title"
    private const val DESCRIPTION = "description"
    private const val ENABLED = "enabled"
    private const val FIBER_PHASE = "fiberPhase"
    private const val CONDITION = "condition"
    private const val CONDITIONAL = "conditional"
    private const val ID = "id"
    private const val NAME = "name"
    private const val IS_DEFAULT = "isDefault"
    private const val BROKEN = "broken"
    private const val ROWS = "rows"
    private const val AT = "at"

    fun parse(stdout: String): DshLiveInventory {
        val marker = stdout.indexOf(MARKER)
        if (marker < 0) {
            // 这一句要能自解释：没有 marker 通常意味着 dsh 压根没起来（覆盖层没挂上、或原生模块加载失败）。
            return DshLiveInventory.Failed("桥没有打出 $MARKER 这一行：dsh 可能没起来，或覆盖层没挂上")
        }
        // 只取 marker 之后的**第一行**：dsh 的其他输出即便混在同一行后面，也不会被吃进 JSON。
        val line = stdout.substring(marker + MARKER.length).lineSequence().first()
        val payload = runCatching { JSONObject(line.trim()) }.getOrElse { error ->
            return DshLiveInventory.Failed("桥打出来的 JSON 读不动：${describe(error)}")
        }
        if (payload.optBoolean(TIMED_OUT, false)) {
            // 超时那份快照是**不完整的**（dsh 还在挂插件）。按完整清单显示等于撒谎：
            // 用户会以为某个插件没挂上，其实只是没等到。所以宁可失败、退回文件视图。
            return DshLiveInventory.Failed("dsh 没在桥的超时时间内把插件挂完，这次的清单不完整")
        }
        textOrNull(payload, ERROR)?.let { reason ->
            return DshLiveInventory.Failed("桥读清单时报错：$reason")
        }
        val array = payload.optJSONArray(ENTRIES)
            ?: return DshLiveInventory.Failed("桥的 JSON 里没有 $ENTRIES 数组")
        val entries = readEntries(array)
        if (entries.isEmpty()) {
            // dsh 的 Loader 里至少有根 include 那一条；空清单说明桥看到的不是真 Loader，
            // 显示成"没有插件"比直接说读不到更误导。
            return DshLiveInventory.Failed("桥打出来的清单是空的（dsh 的 Loader 至少有一条根条目）")
        }
        // 原文一起留着：App 落盘缓存时直接写它，读回来还是这个解析器（见 [Ready.raw]）。
        return DshLiveInventory.Ready(
            entries = entries,
            presets = readPresets(payload.optJSONArray(PRESETS)),
        ).also { it.raw = line.trim() }
    }

    /**
     * 清单原文里的 `at`（会话插件写文件那一刻的墙上时间）；没有 marker、没有 `at`、不是数一律 0。
     *
     * 两处判"这份还新鲜吗"都用它：[DshInventoryProbe] 读会话写出的状态文件、[DshLiveInventoryCache]
     * 读落盘那份缓存 —— 同一份原文、同一个判据，各写一遍就会漂移。
     */
    internal fun timestampOf(text: String): Long {
        val marker = text.indexOf(MARKER)
        if (marker < 0) return 0L
        val line = text.substring(marker + MARKER.length).lineSequence().first()
        return runCatching { JSONObject(line.trim()).optLong(AT, 0L) }.getOrDefault(0L)
    }

    /**
     * 把一段清单原文再包成能被 [parse] 读回的形式（落盘缓存用）。
     *
     * 为什么不是"把这份清单重新序列化一遍"：那要再写一个与 [parse] 对称的序列化器，两处必然漂移。
     * 原文本身就是最保真的形态，而且读回来走的还是同一个解析器。
     */
    internal fun encode(raw: String): String = MARKER + raw.trim() + "\n"

    private fun readEntries(array: JSONArray): List<DshLiveEntry> {
        val entries = ArrayList<DshLiveEntry>(array.length())
        for (index in 0 until array.length()) {
            // 单条坏行只丢这一条：一个畸形条目不该把整页打回文件视图。
            // 没有 entryId 的行本来也没法在界面上寻址。
            val item = array.optJSONObject(index) ?: continue
            val entryId = textOrNull(item, ENTRY_ID) ?: continue
            val meta = item.optJSONObject(META)
            entries += DshLiveEntry(
                entryId = entryId,
                moduleName = textOrNull(item, MODULE_NAME) ?: entryId,
                // 官方投影里 enabled 恒为布尔；缺失时按"开着"处理，比按"关着"少一次误导
                //（关着会让用户以为要自己去开）。
                enabled = booleanOrNull(item, ENABLED) ?: true,
                fiberPhase = textOrNull(item, FIBER_PHASE),
                // 桥没给这个键（或给了空值）就是"这一行没有补丁行 id"：界面据此禁掉它的开关，
                // 而不是拿 entryId 去试（那是 Loader 树 id，写文件按它定位就是改错行）。
                patchId = textOrNull(item, PATCH_ID),
                // 官方那页显示的是标题 + 说明（`pluginPackages` 给的本地化元数据）；
                // 没有它就只能显示包名 —— 真机反馈"官方是 typert-loader / Loader integration…，
                // 而 heta 是一串包名 + 已禁用"。
                title = localizedOrNull(meta, TITLE),
                description = localizedOrNull(meta, DESCRIPTION),
            )
        }
        return entries
    }

    private fun readPresets(array: JSONArray?): List<DshLivePreset> {
        if (array == null) return emptyList()
        val presets = ArrayList<DshLivePreset>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = textOrNull(item, ID) ?: continue
            presets += DshLivePreset(
                id = id,
                name = textOrNull(item, NAME) ?: id,
                isDefault = booleanOrNull(item, IS_DEFAULT) ?: false,
                // 缺这个键 = 官方说的 "absent when rows answers" = 挂得起来。
                broken = textOrNull(item, BROKEN),
                rows = readPresetRows(item.optJSONArray(ROWS)),
            )
        }
        return presets
    }

    private fun readPresetRows(array: JSONArray?): List<DshLivePresetRow> {
        if (array == null) return emptyList()
        val rows = ArrayList<DshLivePresetRow>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val moduleName = textOrNull(item, MODULE_NAME) ?: continue
            // `enabled` 在预设行里可能是布尔，也可能是字符串 `conditional`
            //（那种行由 `condition` 表达式决定）。原样表达，不替它猜一个布尔。
            val raw = item.opt(ENABLED)
            val meta = item.optJSONObject(META)
            rows += DshLivePresetRow(
                entryId = textOrNull(item, ENTRY_ID),
                moduleName = moduleName,
                enabled = raw as? Boolean,
                conditional = raw is String && raw == CONDITIONAL,
                condition = textOrNull(item, CONDITION),
                fiberPhase = textOrNull(item, FIBER_PHASE),
                patchId = textOrNull(item, PATCH_ID),
                title = localizedOrNull(meta, TITLE),
                description = localizedOrNull(meta, DESCRIPTION),
            )
        }
        return rows
    }

    /** 官方 `meta` 里的本地化文本：字面串或 `locale → 文案` 的表；两种都不是就当没有。 */
    private fun localizedOrNull(meta: JSONObject?, key: String): DshLocalizedText? {
        if (meta == null || !meta.has(key) || meta.isNull(key)) return null
        val raw = meta.opt(key)
        return when (raw) {
            is String -> DshLocalizedText.Literal(raw).takeIf { raw.isNotBlank() }
            is JSONObject -> {
                val values = LinkedHashMap<String, String>()
                for (locale in raw.keys()) {
                    val text = raw.optString(locale).takeIf { it.isNotBlank() } ?: continue
                    values[locale] = text
                }
                if (values.isEmpty()) null else DshLocalizedText.Localized(values)
            }

            else -> null
        }
    }

    /** 文本字段：缺失、"null"、空串一律当没有。 */
    private fun textOrNull(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotBlank() }
    }

    private fun booleanOrNull(json: JSONObject, key: String): Boolean? = json.opt(key) as? Boolean

    private fun describe(error: Throwable): String =
        error.message ?: error::class.java.simpleName
}

/**
 * 组合 id / 补丁行 id 的归一化 —— 用来把"预设组合里的行"与"全局清单里的行"对上。
 *
 * 为什么不能直接比：官方投影里组合生成的 id 常带 `include:` 前缀（官方界面显示时才把它省掉），
 * 而全局条目那边不一定带；大小写也不保证一致。所以两边都剥前缀、统一小写再比。没有 id 才退回
 * 模块名 —— 那是两边都有的字段。
 */
internal fun dshLiveRowKey(entryId: String?, moduleName: String?): String {
    val raw = entryId?.takeIf { it.isNotBlank() } ?: moduleName.orEmpty()
    return raw.removePrefix("include:").lowercase()
}

/**
 * 官方那份清单的**短名回退**：`meta.title` 缺席时用它，逐字照抄客户端那段
 *（`dsh-client-ui-settings-plugin-inventory/lib/client.js`）——
 *
 * ```js
 * (moduleName.startsWith("@") ? moduleName.slice(moduleName.indexOf("/") + 1) : moduleName)
 *   .replace(/^cordis:/, "").replace(/^cordis-plugin-/, "").replace(/^dsh-(?:host-|client-)?/, "")
 * ```
 *
 * 也就是：去掉 npm scope，再去掉 `cordis:` / `cordis-plugin-` / `dsh-`（含 `dsh-host-` /
 * `dsh-client-`）前缀。**只用于设置页显示**，详情里照旧给完整模块名（官方也是这么分的）。
 */
internal fun dshModuleShortName(moduleName: String): String {
    val withoutScope = if (moduleName.startsWith("@")) {
        val slash = moduleName.indexOf('/')
        if (slash < 0) moduleName else moduleName.substring(slash + 1)
    } else {
        moduleName
    }
    return withoutScope
        .removePrefix("cordis:")
        .removePrefix("cordis-plugin-")
        .removePrefix("dsh-host-")
        .removePrefix("dsh-client-")
        .removePrefix("dsh-")
}
