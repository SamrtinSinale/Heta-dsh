package io.github.mangi.eta.ui.screens.extensions

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshInventory
import io.github.mangi.eta.agent.dsh.DshInventoryBundle
import io.github.mangi.eta.agent.dsh.DshInventoryProbe
import io.github.mangi.eta.agent.dsh.DshInventoryRow
import io.github.mangi.eta.agent.dsh.DshLiveEntry
import io.github.mangi.eta.agent.dsh.DshLiveInventory
import io.github.mangi.eta.agent.dsh.DshLiveInventoryCache
import io.github.mangi.eta.agent.dsh.DshPluginInventory
import io.github.mangi.eta.agent.dsh.DshPresetPlane
import io.github.mangi.eta.agent.dsh.DshPresetSelection
import io.github.mangi.eta.agent.dsh.DshProfileStore
import io.github.mangi.eta.agent.dsh.DshProfileWrite
import io.github.mangi.eta.agent.dsh.DshRowState
import io.github.mangi.eta.agent.dsh.DshRuntimeInstaller
import io.github.mangi.eta.ui.app.DshPresetUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「扩展」页面的局部状态持有者：一次读（清单）、两种写（bundle 选中、插件行开关）。
 *
 * 为什么跟着页面走而不是 ViewModel：这里的状态不跨页面共享，也没有需要活过组合的草稿；
 * 页面一走那次读/写就断掉，反而不会留下一个还在写文件的孤儿（形制同 `SpeechSettingsStore`）。
 *
 * **起一次 dsh 很贵**（[DshInventoryProbe] 要真跑一遍运行时，手机上几秒级），所以只有两种情况会探：
 *   · 进页面时**没有**活清单缓存（第一次，界面得等着：[liveLoading]）；
 *   · 缓存过期（超过 [DshLiveInventoryCache] 的 TTL）或被标脏，以及用户按了顶部刷新
 *     —— 这两种都**先渲染旧的**，再在后台更新一次，不打回 loading。
 * 其余动作（最要紧的是：开关一行插件）**绝不起 dsh**：写完文件就地改本地状态（[pending]），
 * 活清单缓存只标脏。
 *
 * 读与写不再共用 busy：一次探针可能跑十几秒，拿它挡住开关就是"点了没反应"（真机反馈④）。
 * 两者重叠也不会把界面搅乱 —— 写完的那一行由 [pending] 说了算，晚到的探针结果覆盖不了它。
 */
internal class DshExtensionsStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    // 只留 application context：一次绕 IO 的写可能比页面活得久一点。
    private val appContext = context.applicationContext

    /** 首次读取的占位。刷新时不回到 true —— 否则每点一次刷新整页都闪一下空白。 */
    var loading by mutableStateOf(true)
        private set

    /** 运行时没装好。这不是"没有插件"，界面上要与"清单为空"分开说。 */
    var runtimeReady by mutableStateOf(true)
        private set

    /** 一次**写**在飞：期间禁掉开关与刷新（两个写重叠就会互相盖掉，白点一下）。 */
    var working by mutableStateOf(false)
        private set

    /** null = 还没读到（首次加载中，或读失败）。 */
    var inventory by mutableStateOf<DshInventory?>(null)
        private set

    /**
     * 活清单（dsh 进程里的真实状态）。null = 取不到，此时页面退回 [inventory] 的文件视图。
     *
     * 为什么与 [inventory] 并存而不是取代它：bundle 的开关、写文件的目标、以及"补丁层哪里坏了"
     * 只有文件视图知道；活清单知道的是"dsh 到底挂了什么、跑成什么样"。两条路径各自都有对方看不见
     * 的东西，所以都留着。
     */
    var live by mutableStateOf<DshLiveInventory.Ready?>(null)
        private set

    /** 探针失败的原因。界面把它**显示出来**再退回文件视图 —— 不能因为探针失败就让整页空白。 */
    var liveProblem by mutableStateOf<String?>(null)
        private set

    /**
     * 探针在读，而且界面上**还没有任何活清单**可显示（第一次进页面）。
     *
     * 有缓存时后台更新**不**置它：那种情况下页面照旧渲染缓存，用户看不到"正在读取"（真机反馈①）。
     * 它要真起一次 dsh，几秒级；没有这个标志的话，第一次进页面那一下看起来就是"点了没反应"。
     */
    var liveLoading by mutableStateOf(false)
        private set

    /**
     * 活清单这一轮读完没有。
     *
     * 界面拿它挡一件事：文件视图与活清单是**两套数据**，开关的口径不一样（文件视图按补丁行、
     * 活清单按运行中的条目），中间态就把开关画出来会"先出来一个、下一帧又没了"（真机反馈⑤）。
     * 所以它是 false 时界面一行开关都不画，只显示"正在读取"；一旦落定就**不再**回到 false ——
     * 同一个页面里，同一行不该一会儿有开关一会儿没有。
     */
    var liveSettled by mutableStateOf(false)
        private set

    /**
     * 探针在飞（一次只允许一个）。
     *
     * 它**不等于** [working]：探针期间开关照旧要能点（真机反馈②④），只是刷新按钮先别点。
     */
    var probing by mutableStateOf(false)
        private set

    /** 一次"读清单"在飞（含后台更新）：只用来挡住重复进来，不挡界面上的开关。 */
    private var loadingNow = false

    /**
     * 本地刚改过的行：补丁行 id → 用户点出来的开 / 关。
     *
     * 为什么需要它：写完文件 dsh 并不会立刻重载（hmr 在 profile 里是关的），活清单里的 `enabled`
     * 仍然是**改之前**那个值 —— 不拿它压住，开关就会"自己弹回去"。它也顺手解决另一件事：
     * 界面不再为了显示一个开关去重读任何东西。
     */
    private val pending = mutableStateMapOf<String, Boolean>()

    /** bundle 的本地改写：名字 → 用户在界面上选 / 禁的结果（同上，写完不再重读清单）。 */
    private val pendingBundles = mutableStateMapOf<String, Boolean>()

    /**
     * 当前选中的预设（[DshPresetPlane] 里那四个之一）。
     *
     * 它是**下一轮 run** 会用的那个：覆盖层每轮重新生成，`config.default` 就取这个值。活清单
     * 里的 `isDefault` 要等下一轮 run 才会跟着变，所以页面的"新任务默认"标记看的是这里，不是
     * 对方报的那个 —— 否则用户刚点完会看到"没生效"。
     *
     * **直接读进程内那一份**（[DshPresetUi]），这一页不再自存一个副本：聊天页顶栏也能切
     * 预设，各存一份就会出现"在那边切了、这边还写着旧名字"。
     */
    val selectedPresetId: String get() = DshPresetUi.current(appContext)

    /** 结果提示；写入被拒绝时它就是数据层给的理由原文。 */
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set

    init {
        // 偏好读失败不该拦着读清单：选不中就用官方默认值（[DshPresetUi] 里那一次读会兜）。
        load(explicit = false)
    }

    /** 顶部那个刷新按钮：显式重探一次 —— 界面上唯一一个"一定会起 dsh"的入口。 */
    fun reload() = load(explicit = true)

    /**
     * 读一次清单。
     *
     * [explicit] = true 只来自顶部那个刷新按钮（它一定要真探一次，不然那个按钮就没有意义了）；
     * false 是"进页面"，按缓存来（见类头那段）。
     */
    private fun load(explicit: Boolean) {
        if (loadingNow) return
        loadingNow = true
        scope.launch {
            try {
                read(explicit)
            } finally {
                loadingNow = false
                loading = false
            }
        }
    }

    private suspend fun read(explicit: Boolean) {
        val ready = withContext(Dispatchers.IO) { DshRuntimeInstaller.isReady(appContext) }
        runtimeReady = ready
        if (!ready) {
            // 运行时不在时读出来只会是一屏"找不到这个包"：那不是这个页面要说的事。
            inventory = null
            live = null
            liveProblem = null
            liveSettled = true
            return
        }
        readFileInventory()
        // 文件视图一落地就放开 loading：探针要几秒，没必要让整页干等它（这一页的底线是不空白）。
        loading = false
        readLiveInventory(explicit)
        // 补一次重读：**dsh 自己会在第一次运行时把 profile 建出来**（package.json + cordis.patch.yml），
        // 而上面那次读发生在探针之前 —— 运行时刚被重新解包（REVISION 变了）时，读到的就是一个还没
        // 初始化的 profile：`DshPluginInventory` 只把"选中"的 bundle 记账，selected 为空 ⇒ 补丁层
        // 一行都没有 ⇒ 页面上"所有开关都不见了、两个插件包还显示成关着"（2026-10-04 真机反馈）。
        // 探针这一次已经把 dsh 跑起来了，profile 此刻已经存在，再读一次就与真实组合一致。
        if (inventory?.rows.isNullOrEmpty()) {
            readFileInventory()
        }
    }

    /**
     * 读**文件视图**：补丁层合并出来的那张表。很便宜 —— 只读那几个文件，不起 dsh。
     *
     * 单独抽出来是因为"读"不再只有一条路：活清单有缓存时只走这一步。
     */
    private suspend fun readFileInventory() {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val runtimeRoot = DshRuntimeInstaller.runtimeDirectory(appContext)
                // profile 目录是 dsh（root）建的：App 读得到、写不进去（连在里面建临时文件都被拒）。
                // 读清单本身不需要写权限，但用户下一步就是点开关 —— 所以在这里先把 owner 修好，
                // 免得到时候"第一次点必然失败"。修完仍写不进时，写那边会把真实错因显示出来。
                // 这一步会走 su（阻塞），必须在 IO 上下文里。
                val profileDir = DshProfileStore.forRuntime(runtimeRoot).profileDir
                if (!profileDir.canWrite()) DshRuntimeInstaller.handProfileToApp(profileDir)
                // profile 名两边都靠默认值（acp）：各写一遍反而会漂移。
                DshPluginInventory(
                    runtimeRoot = runtimeRoot,
                    presetManagedRowIds = DshPresetPlane.managedRowIdsFor(appContext),
                    // 平面直接由资产生成、不依赖覆盖层文件存在（见 DshPresetPlane.planeRows）：
                    // 刚重装完运行时、还没开始任何对话时，文件视图也能如实显示这几行。
                    presetPlaneRows = DshPresetPlane.planeRows(
                        DshPresetPlane.reader(appContext),
                        DshPresetPlane.selectedFor(appContext),
                    ),
                ).read()
            }
        }
        result.fold(
            onSuccess = { inventory = it },
            onFailure = { error ->
                if (error is CancellationException) throw error
                inventory = null
                fail(R.string.extensions_load_failed, error)
            },
        )
    }

    /**
     * 读**活清单**（dsh 进程里那份）：**有缓存就直接渲染，没有（或过期 / 显式刷新）才起 dsh**。
     *
     * 活清单是**独立一步**：文件视图读坏了不影响它，它失败也只让插件行退回文件视图 ——
     * 两条路径谁也不许把对方判死（这一页的底线是不能空白）。
     */
    private suspend fun readLiveInventory(explicit: Boolean) {
        try {
            val cached = DshLiveInventoryCache.get()
            if (cached != null) {
                // 先给旧的：**绝不**因为"它可能不准"就打回 loading（真机反馈①）。
                live = cached
                liveProblem = null
                if (!explicit && !DshLiveInventoryCache.isStale()) return
            }
            if (probing) return
            probing = true
            // 只有"什么都没有"时才让界面说"正在读"；后台更新不打扰已经显示出来的那一屏。
            liveLoading = cached == null
            try {
                // 探针会走 su + chroot（阻塞在 waitFor 上），必须在 IO 上下文里。
                when (val probe = withContext(Dispatchers.IO) { DshInventoryProbe.read(appContext) }) {
                    is DshLiveInventory.Ready -> {
                        DshLiveInventoryCache.put(probe)
                        live = probe
                        liveProblem = null
                    }

                    is DshLiveInventory.Failed -> {
                        // 失败**不动缓存**：旧的那份还能看（页面不空白），原因单独说一句。
                        if (cached == null) live = null
                        liveProblem = probe.reason
                    }
                }
            } finally {
                probing = false
                liveLoading = false
            }
        } finally {
            // 读到什么都算这一轮结束：从现在起界面可以放心画开关了（见 [liveSettled]）。
            liveSettled = true
        }
    }

    /**
     * 活清单里的一行 → 文件视图（补丁层）里那一行。查不到就返回 null。
     *
     * 先按 [DshLiveEntry.patchId] 查 —— 那是**补丁行 id**（官方 `listPlugins` 里同一个东西），
     * 与补丁层里的行是同一个 id 空间，一查就中。查不到再退回老办法：补丁层里写的是 `acp` / `hmr`，
     * 而 Loader 树里的 id 是 `include:acp`（dsh 的 include 插件给子条目加了"父 id + 冒号"的前缀，
     * 根那条 `cordis:include` 的 id 就是 `include`，本机实测），所以再去掉第一段前缀试一次。
     *
     * **这一行只用来显示**（补丁层里那一行的来源、"管理模块不许关"这条理由）。开关写文件直接用
     * [DshLiveEntry.patchId]，不再依赖这里查得到查不到 —— 查不到只是少几条详情（真机反馈④：
     * 以前查不到就整行不给开关）。
     *
     * 不做"按模块名猜"的兜底：同一个模块名可能对应好几行（实测 `@deepseek-ai/dsh-tool-subagent`
     * 同时是 `tool-subagent` 与 `tool-subagent-fork`），猜错就是改错行。
     */
    fun patchRowFor(entry: DshLiveEntry): DshInventoryRow? {
        val rows = inventory?.rows ?: return null
        entry.patchId?.let { patchId -> rows.firstOrNull { it.patchId == patchId }?.let { return it } }
        val namespaced = entry.entryId.substringAfter(':', missingDelimiterValue = "")
        return rows.firstOrNull { it.patchId == entry.entryId }
            ?: rows.firstOrNull { namespaced.isNotEmpty() && it.patchId == namespaced }
    }

    /**
     * 这一行（活清单）的开关该显示成什么。
     *
     * 本地刚点过的那个值优先：写完文件 dsh 并没有重载，活清单里的 `enabled` 还是旧的 ——
     * 照它显示就是"开关点了又自己弹回去"。
     */
    fun enabledFor(entry: DshLiveEntry): Boolean = entry.patchId?.let { pending[it] } ?: entry.enabled

    /** 文件视图那一行的配置状态（同上：本地刚点过的优先）。 */
    fun stateFor(row: DshInventoryRow): DshRowState =
        pending[row.patchId]?.let { if (it) DshRowState.ENABLED else DshRowState.DISABLED } ?: row.state

    /** bundle 的选中状态（同上）。 */
    fun bundleSelected(bundle: DshInventoryBundle): Boolean =
        pendingBundles[bundle.name] ?: bundle.selected

    /** 选 / 禁一个 bundle：改 profile 清单里的 `dsh.profile.bundles`。 */
    fun setBundleSelected(name: String, selected: Boolean) {
        write(
            action = { profile -> profile.setBundleSelected(name, selected) },
            applied = { pendingBundles[name] = selected },
        )
    }

    /**
     * 开 / 关一行插件：按**补丁行 id** 往 profile 补丁层写这一行的 `disabled`。
     *
     * [patchId] 就是 [DshLiveEntry.patchId] / [DshInventoryRow.patchId]（补丁行 id，**不是** Loader
     * 树 id）；[moduleName] 只在这一行还不存在于补丁层里、要新加一条覆盖行时用得上。
     *
     * 写完**不重读、不重探**：dsh 要下一次对话才重载，这里只把这一行在本地改掉（[pending]），
     * 并把活清单缓存标脏 —— 下次（显式或过期后的后台）刷新才会真去探一遍。
     */
    fun setPluginEnabled(patchId: String, moduleName: String?, enabled: Boolean) {
        write(
            action = { profile -> profile.setPluginEnabled(patchId, moduleName, enabled) },
            applied = {
                pending[patchId] = enabled
                // 活缓存里那一行已经不是现状了：下次要真探一次，别拿旧的糊弄。
                DshLiveInventoryCache.invalidate()
            },
        )
    }

    /**
     * 选中一个预设：下一次开对话生效。
     *
     * 写的是 App 的偏好、**不是**补丁层：`config.default` 由覆盖层承载，而覆盖层每轮 run 重新
     * 生成（见 [DshPresetPlane]），所以这里只记住 id，别的什么都不用动 —— 与这一页其它改动同一句
     * 语义（下次开对话才生效）。也**不重探**：活清单里那份 roster 的 `isDefault` 是这一轮 run 的
     * 事实，改不了；页面按本地选择显示"新任务默认"。
     */
    fun selectPreset(id: String) {
        // 点已选中的那一行：照旧给一句「当前已是该状态，未做改动」—— 静默反而让人以为没点上
        //（真机反馈：上一版把这条提示删了）。
        //
        // 用 Toast 而不是页面里那行文字：页面级提示与预设段的分区说明会挤在一起，真机反馈
        // "那个字还是与提示重叠"。瞬时反馈浮在底部，页面里只留分区说明。
        if (id == selectedPresetId) {
            notice(R.string.extensions_message_unchanged)
            return
        }
        // 预设不分文件层的写路径：这里不动 profile 补丁层，所以不走 write()（那个路径会去修 owner、
        // 锁 working）。commit() 是一次很小的磁盘写，放 IO 里。
        scope.launch {
            val written = withContext(Dispatchers.IO) { DshPresetSelection.select(appContext, id) }
            if (!written) {
                fail(R.string.extensions_write_failed, IllegalStateException("预设选择没写进偏好"))
                return@launch
            }
            // 发布到进程内那一份：聊天页顶栏也跟着变（一个值两个入口）。这一步在主线程上
            // （上面那次 withContext(IO) 已经回来了）。
            DshPresetUi.publish(id)
            // 生效时机由预设段的分区说明负责讲（"切换后于下一条消息生效"），这里只说发生了什么。
            notice(R.string.extensions_preset_saved)
        }
    }

    /**
     * 共用的写路径：一次只允许一个写。
     *
     * [applied] 只在**写成功**之后跑（被拒 / 抛异常时界面照旧按旧状态显示 —— 那才是真的）。
     */
    private fun write(
        action: (DshProfileStore) -> DshProfileWrite,
        applied: () -> Unit,
    ) {
        if (working) return
        working = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 每次重取目录：重装运行时会换掉整棵树，缓存下来的 File 会指向旧的那一棵。
                    val runtimeRoot = DshRuntimeInstaller.runtimeDirectory(appContext)
                    val profile = DshProfileStore.forRuntime(runtimeRoot)
                    // 兜底再修一次：dsh 自己（root）用 writeFileAtomic 覆盖过这两个文件时，
                    // owner 会变回 root —— 那时这里会把它再交还给自己，然后照常原子写。
                    if (!profile.profileDir.canWrite()) {
                        DshRuntimeInstaller.handProfileToApp(profile.profileDir)
                    }
                    action(profile)
                }
            }
            working = false
            result.fold(
                onSuccess = { settle(it, applied) },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    fail(R.string.extensions_write_failed, error)
                },
            )
        }
    }

    private fun settle(result: DshProfileWrite, applied: () -> Unit) {
        when (result) {
            is DshProfileWrite.Ok -> {
                // changed=false（文件里本来就是想要的那个状态）也要改本地显示：用户刚点的那个值
                // 就是界面该显示的值，而文件确实没动（提示文案说的是"没写"）。
                applied()
                message = appContext.getString(
                    if (result.changed) {
                        R.string.extensions_message_saved
                    } else {
                        R.string.extensions_message_unchanged
                    },
                )
                messageIsError = false
            }

            // 拒绝的理由只在这里能看到，原样显示：它是数据层的中文文案，包一层反而盖住了原因。
            is DshProfileWrite.Rejected -> {
                message = result.reason
                messageIsError = true
            }
        }
    }

    /** 瞬时反馈走 Toast：页面里那行提示留给需要回看的错误原文（见 [fail]）。 */
    private fun notice(resourceId: Int) {
        runCatching {
            Toast.makeText(appContext, appContext.getString(resourceId), Toast.LENGTH_SHORT).show()
        }
    }

    private fun fail(resourceId: Int, error: Throwable) {
        message = appContext.getString(resourceId, error.message ?: error::class.java.simpleName)
        messageIsError = true
    }
}
