package io.github.mangi.eta.agent.dsh

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「扩展」页面要显示的东西：把 dsh 真会挂载的那几层补丁合并成一张插件表。
 *
 * 层序**逐字对齐** dsh 自己的 `--dump-config`（`collectConfigDumpLayers`）与
 * `readProfilePatches`：
 *
 *     bundles（按 `dsh.profile.bundles` 顺序）→ profile 的 cordis.patch.yml
 *     → `$DSH_HOME/cordis.patch.yml`（home 层）→ 我们的 heta-run-overlay.patch.yml（最后生效）
 *
 * 为什么是**读文件**而不是跑 `dsh --dump-config`：那条路要在真机上经 su/chroot 起 node，
 * 是个只能靠 CI（qemu）验证的启动路径，而这个页面要的是"点开就能看见"；纯文件读还能进本地
 * 10 秒回路（`scripts/test-local-dsh.sh`）。代价是这套合并规则是**抄**来的，所以另配一条交叉
 * 验证：`scripts/test-dsh-inventory.sh` 在 CI 里真跑一次 dsh 自己的 `--dump-config`，再由
 * `DshPluginInventoryTest.matchesTheRuntimesOwnComposedTree` 把它吐出来的行与本类算出来的行
 * 逐条对比（首次接上时对着真运行时比过：96 行全中）。
 *
 * 合并规则（来自 dsh 自己的注释，不是猜的）：
 *   · 补丁按 **id 定位**、逐字段盖到目标行上 —— "the patch algorithm only rewrites rows in place
 *     or appends" ⇒ 行的位置 = 首次出现的地方，值 = 最后写的那一层；
 *   · 例外是 `config`：整块替换、不做深合并（`dsh-base/cordis.patch.yml` 自己写明了）。我们只读
 *     id/name/disabled，用不到这条，但合并 `disabled` 时必须知道"没提的字段不动"
 *     （见 [DshPatchRow.overridesDisabled]）；
 *   · `insert:` 条目是**分组**：它的 id 不是插件，里面的子行才是（官方 `flatten` 就是这么算的）；
 *   · bundle 只要有一处读不出来，**整层跳过**并把原因报出来（官方 `loadProfileDirectory` 的
 *     try/catch 就是这个粒度）。profile 自己的补丁层坏了则不同：官方那边是直接抛错、dsh 起不来，
 *     所以这里也把它报成问题而不是悄悄跳过。
 *
 * **做不到的**（照实说）：官方还会按 peer/版本策略跳过某些 bundle（`evaluatePluginCompatibility`），
 * 那要读运行时的策略与豁免文件，这里没有实现 —— 真出现这种包，本类会多列它的行。CI 的
 * `--dump-config` 交叉验证就是拿来照这种偏差的。
 */
internal class DshPluginInventory(
    private val runtimeRoot: File,
    private val profile: String = "acp",
    /**
     * 被预设接管的行 id（来自 `assets/heta-presets/plane-disable.patch.yml`）。
     *
     * 这些行在覆盖层里每轮 run 都被按回 `disabled: true`，所以单独开关它们**不会有任何效果**；
     * 与其让用户点了没反应，不如在那一行上把原因说清楚。默认空集合：单测与"没有预设资产"的
     * 情况下行为不变。
     */
    private val presetManagedRowIds: Set<String> = emptySet(),
    /**
     * 预设平面那几行（`DshPresetPlane.planeRows`）。
     *
     * 为什么不直接读覆盖层文件：覆盖层是**每轮 run 才生成**的，而这一页可能在"那次 run 还没
     * 发生"时被打开（刚重装完运行时就是这种状态）—— 那时文件层里一行平面都没有，页面上就会把
     * 正在跑的插件标成"配置里还没有这一行"（真机反馈），而且刷新也刷不出来。
     * 这一层的文本与每轮写进覆盖层的那份**同源**（同一个生成函数），所以不会漂移。
     */
    private val presetPlaneRows: List<DshPatchRow> = emptyList(),
) {

    private val profileDir: File = File(runtimeRoot, "root/.dsh/profiles/$profile")
    private val installationDir: File = File(runtimeRoot, "opt/dsh")
    private val homePatchFile: File = File(runtimeRoot, "root/.dsh/cordis.patch.yml")
    private val overlayFile: File = File(runtimeRoot, "opt/dsh/heta-run-overlay.patch.yml")

    fun read(): DshInventory {
        val selected = DshProfileStore(profileDir).read().bundles
        val profileDependencies = readDependencies(File(profileDir, "package.json"))
        val installationDependencies = readDependencies(File(installationDir, "package.json"))

        val layers = ArrayList<DshPatchLayer>()
        val bundles = ArrayList<DshInventoryBundle>()
        for (name in catalogue(selected, profileDependencies, installationDependencies)) {
            val isSelected = name in selected
            val installed = name in profileDependencies || name in installationDependencies
            val bundleDir = resolveBundleDir(name)
            val files = bundleDir?.let { bundlePatchFiles(it) }
            if (files == null) {
                // 官方 `listBundles`：**没选中**、又不是 bundle 的依赖根本不列 —— 安装自带的依赖里
                // 绝大多数是插件包（实测随包运行时 82 个依赖里只有 2 个是 bundle），全列出来只会糊满
                // 一屏"它不是 bundle"。只有选中的才报这个原因。
                if (isSelected) {
                    bundles += DshInventoryBundle(
                        name = name,
                        selected = true,
                        installed = installed,
                        isBundle = false,
                        rowCount = 0,
                        problem = if (bundleDir == null) {
                            "找不到这个包：安装目录和 profile 的 node_modules 里都没有"
                        } else {
                            "这个包没有声明 dsh.bundle.patch —— 它不是 bundle"
                        },
                        readOnlyReason = null,
                    )
                }
                continue
            }
            val read = readLayer(files)
            if (isSelected && read.problem == null) layers += DshPatchLayer(name, read.rows)
            bundles += DshInventoryBundle(
                name = name,
                selected = isSelected,
                installed = installed,
                isBundle = true,
                rowCount = read.rows.size,
                problem = read.problem,
                // 官方的口径：这一层的行里只要碰到管理模块，这一层就是"管理必需"，不许关。
                readOnlyReason = if (read.rows.any { it.moduleName in PROTECTED_MODULES }) {
                    "该包包含运行时的管理模块；禁用后运行时将无法启动。"
                } else {
                    null
                },
            )
        }

        val problems = ArrayList<String>()
        optionalLayer("profile 补丁层", File(profileDir, "cordis.patch.yml"), layers, problems)
        optionalLayer("home 补丁层", homePatchFile, layers, problems)
        optionalLayer("Heta 覆盖层", overlayFile, layers, problems)
        // 平面排在最后（它也是真启动里优先级最高的那一层）。覆盖层已经存在时，这两处的行是同一批
        // patchId，[merge] 会并按 id 合并 —— 幂等，不会出现两份。
        if (presetPlaneRows.isNotEmpty()) {
            layers += DshPatchLayer("Heta 预设平面", presetPlaneRows)
        }

        return DshInventory(bundles = bundles, rows = merge(layers), problems = problems)
    }

    /** 该列哪些包：清单里选中的 + profile 声明过的依赖 + 安装自带依赖（顺序同官方）。 */
    private fun catalogue(
        selected: List<String>,
        profileDependencies: List<String>,
        installationDependencies: List<String>,
    ): List<String> = LinkedHashSet<String>().apply {
        addAll(selected)
        addAll(profileDependencies)
        addAll(installationDependencies)
    }.toList()

    /**
     * 解析一个 bundle 的几个补丁文件；任何一处读不出来就整层作废（同官方 `loadProfileDirectory`）。
     */
    private fun readLayer(files: List<File>): LayerRead {
        val rows = ArrayList<DshPatchRow>()
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull()
                ?: return LayerRead(emptyList(), "它的补丁文件读不到：${file.name}")
            val document = DshPatchDocument.parse(text)
            document.rejection?.let { return LayerRead(emptyList(), "${file.name} 不是能读的补丁：$it") }
            rows += document.rows
        }
        return LayerRead(rows, null)
    }

    /** 用户那几层：文件不在就跳过（官方 `existsSync` 判断），在了但读不出来就报问题。 */
    private fun optionalLayer(
        label: String,
        file: File,
        layers: MutableList<DshPatchLayer>,
        problems: MutableList<String>,
    ) {
        if (!file.isFile) return
        val read = readLayer(listOf(file))
        read.problem?.let { problems += "$label（${file.name}）：$it"; return }
        layers += DshPatchLayer(label, read.rows)
    }

    /**
     * 逐字段合并：位置按首次出现，值按最后写的那层；`disabled` 只有那层**提了**才生效。
     */
    private fun merge(layers: List<DshPatchLayer>): List<DshInventoryRow> {
        val byId = LinkedHashMap<String, MutableRow>()
        for (layer in layers) {
            for (row in layer.rows) {
                val current = byId[row.patchId]
                if (current == null) {
                    byId[row.patchId] = MutableRow(row, layer.label)
                    continue
                }
                if (row.moduleName != null) current.moduleName = row.moduleName
                if (row.overridesDisabled) current.state = row.state
                if (layer.label != current.source) current.mentionedBy += layer.label
            }
        }
        return byId.values.map {
            DshInventoryRow(
                patchId = it.patchId,
                moduleName = it.moduleName,
                state = it.state,
                source = it.source,
                mentionedBy = it.mentionedBy.toList(),
                // 用**合并后**的模块名判定：覆盖行常常不写 name，声明层那个才是真正在跑的模块。
                readOnlyReason = when {
                    // 措辞要把"它为什么是关的"和"这一页为什么改不了"分开说：这一类里有的行
                    //（例如 hmr）本来就是 profile 自己关着的，而保护名单管的是"别在这一页关掉它"。
                    // 真机反馈"被关掉了没法运行我还没法控制"，就是把两件事读成了一句。
                    it.moduleName in PROTECTED_MODULES ->
"该项为运行时的管理模块；禁用后运行时将无法启动，因此此处不提供开关。"
                    it.moduleName == null -> "该项缺少模块名，无法定位到具体插件。"
                    // 放在最后：官方那 8 个管理模块属于"关掉就起不来"，理由更硬，先报那一个。
                    it.patchId in presetManagedRowIds -> "该项由预设统一管理，此处不提供单独开关。"
                    else -> null
                },
            )
        }
    }

    /** 一个包的目录：**安装目录优先**（官方契约：自带的 bundle 永远来自同一份安装），再找 profile。 */
    private fun resolveBundleDir(packageName: String): File? {
        for (anchor in listOf(installationDir, profileDir)) {
            for (nodeModules in nodeModulesPaths(anchor)) {
                val candidate = File(nodeModules, packageName)
                if (File(candidate, "package.json").isFile) return candidate
            }
        }
        return null
    }

    /** Node 的 node_modules 上溯：从 [dir] 逐级向上，每级一个 `node_modules`。 */
    private fun nodeModulesPaths(dir: File): List<File> {
        val paths = ArrayList<File>()
        var current: File? = dir.absoluteFile
        while (current != null) {
            paths += File(current, "node_modules")
            current = current.parentFile
        }
        return paths
    }

    /** bundle 自己声明的补丁文件（包内相对路径）；`null` = 它没声明或声明的形状不合法。 */
    private fun bundlePatchFiles(bundleDir: File): List<File>? {
        val manifest = runCatching { JSONObject(File(bundleDir, "package.json").readText()) }.getOrNull()
            ?: return null
        val declared = manifest.optJSONObject("dsh")?.optJSONObject("bundle")?.opt("patch") ?: return null
        val names = when (declared) {
            is String -> listOf(declared)
            is JSONArray -> (0 until declared.length()).mapNotNull { index ->
                declared.optString(index).takeIf { it.isNotBlank() }
            }

            else -> return null
        }
        if (names.isEmpty()) return null
        return names.map { File(bundleDir, it) }
    }

    /** 清单里的依赖名。**排序过的**：org.json 的键序不保证稳定，界面上要一个确定的顺序。 */
    private fun readDependencies(manifest: File): List<String> {
        val json = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return emptyList()
        val dependencies = json.optJSONObject("dependencies") ?: return emptyList()
        return dependencies.keys().asSequence().sorted().toList()
    }

    private class LayerRead(val rows: List<DshPatchRow>, val problem: String?)

    private class DshPatchLayer(val label: String, val rows: List<DshPatchRow>)

    private class MutableRow(row: DshPatchRow, val source: String) {
        val patchId: String = row.patchId
        var moduleName: String? = row.moduleName
        var state: DshRowState = row.state
        val mentionedBy = LinkedHashSet<String>()
    }
}

/** 「扩展」页面的一次读取结果。 */
internal data class DshInventory(
    /** 清单里选中的、profile 依赖里的、安装自带的包 —— 开关 bundle 用这一份。 */
    val bundles: List<DshInventoryBundle>,
    /** 合并后的插件行，按首次出现顺序；开关状态以它为准。 */
    val rows: List<DshInventoryRow>,
    /** 层本身的问题（bundle 的问题在各自的 `problem` 里），界面照实显示。 */
    val problems: List<String>,
)

/** 一个 bundle（或"被选中但其实不是 bundle"的包）。 */
internal data class DshInventoryBundle(
    val name: String,
    /** 在 `dsh.profile.bundles` 里。 */
    val selected: Boolean,
    /** 作为依赖装在 profile 里（安装自带的那些不算）。 */
    val installed: Boolean,
    /** 能读到 `dsh.bundle.patch`。 */
    val isBundle: Boolean,
    /** 它自己声明了多少行（没选中或读不出来时是 0）。 */
    val rowCount: Int,
    /** 读不出来 / 不是 bundle 的原因。 */
    val problem: String?,
    /** 不许在界面上关掉的原因（null = 可以关）。见 [PROTECTED_MODULES]。 */
    val readOnlyReason: String?,
)

/** 合并后的一行插件。 */
internal data class DshInventoryRow(
    /** 补丁行的 id —— 开关就是按它写覆盖行。 */
    val patchId: String,
    /** 模块名；只写覆盖的层通常不写，合并后保留声明层给的那个。 */
    val moduleName: String?,
    val state: DshRowState,
    /** 哪个层带进来的（bundle 包名 / `profile 补丁层` / `home 补丁层` / `Heta 覆盖层`）。 */
    val source: String,
    /**
     * 它后面还有哪些层**提到过**它（含只改 `config` 的层 —— 那种改动本类看不见）。
     * 官方的 dump 标注是 "patched by …"，口径比这个严；这里宁可说宽一点，也不假装知道 config 变没变。
     */
    val mentionedBy: List<String>,
    /** 不许在界面上关掉的原因（null = 可以关）。见 [PROTECTED_MODULES]。 */
    val readOnlyReason: String?,
)

/**
 * 不许在界面上关掉的模块。
 *
 * 前 16 个**逐字抄自官方 `protectedModules`**（`dsh-plugin-manager/lib/index.js` 第 1077 行起），
 * 官方的 `listPlugins` 用它们把这类行标成 `management-required`。官方保护的是**可恢复性**：
 * 这些是"把别的插件关坏了之后，还能把运行时改回来"所依赖的管理模块。
 *
 * 后两个是 Heta 自己加的：`dsh-acp-app` / `dsh-acp` 是 Heta 跟 dsh 说话的唯一入口 —— 关掉它，
 * 下一次对话就是 `initialize` 失败 / protocol-eof（这个 App 自己还能改回来，但会先炸一次，
 * 而"炸一次"正是我们要防的）。
 */
private val PROTECTED_MODULES = setOf(
    "@deepseek-ai/dsh-plugin-manager",
    "@deepseek-ai/cordis-plugin-loader",
    "@deepseek-ai/cordis-plugin-include",
    "@deepseek-ai/dsh-api-gateway",
    "@deepseek-ai/dsh-host-webserver",
    "@deepseek-ai/dsh-client-modules",
    "@deepseek-ai/dsh-client-ui-settings-plugin-inventory",
    "@deepseek-ai/dsh-client-ui-plugin-manager",
    "@deepseek-ai/dsh-host-plugin-inventory",
    "@deepseek-ai/dsh-typert-registry",
    "@deepseek-ai/dsh-api-remotes",
    "@deepseek-ai/cordis-plugin-timer",
    "@deepseek-ai/dsh-client-connection",
    "@deepseek-ai/dsh-host-frontend-static",
    "@deepseek-ai/dsh-tools",
    "@deepseek-ai/dsh-hmr",
    "@deepseek-ai/dsh-acp-app",
    "@deepseek-ai/dsh-acp",
)
