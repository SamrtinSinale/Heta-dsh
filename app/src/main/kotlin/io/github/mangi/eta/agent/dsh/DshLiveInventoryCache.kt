package io.github.mangi.eta.agent.dsh

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 活清单的**进程级 + 落盘**缓存。
 *
 * 为什么需要它：读一次活清单要**真起一次 dsh**（[DshInventoryProbe]）——手机上几秒级（arm64 + qemu
 * 实测 ~27 秒），而这份数据只在"dsh 真的换过插件"之后才会变。进页面、开关一行插件这些动作都不该让
 * 它重跑一遍。[DshExtensionsStore] 是页面级对象（`remember {}` 出来的），缓存挂在它身上只能活一次
 * 页面，所以放在这个进程级单例里：页面反复进出，数据还在。
 *
 * **而且落盘**（[attach] + [restore]）：进程级缓存活不过一次 App 重启，而"每次重启后第一次点开
 * 扩展页都要等 ~27 秒"就是这么来的（真机反馈原话："他一直都在那里加载"）。落盘之后，重启后第一次
 * 进页面就有东西可画 —— 旧的那份照旧先渲染，过期只触发一次**后台**更新（[isStale]）。
 *
 * 分工：
 *   · [get] 拿快照 —— 页面**先渲染它**：有缓存就绝不起 dsh（先给东西看，再谈准不准）；
 *   · [restore] 把落盘那份读回内存（**IO 上调用**，一辈子只真读一次）；
 *   · [isStale] 判"过期" —— 超过 [TTL_MS] 或刚被 [invalidate] 标脏。过期**不**妨碍渲染，
 *     只让页面在后台悄悄更新一次，不打回 loading；
 *   · [loadOnce] 一次真探（同一时刻只允许一个，与 App 起来那次预热并成一次）；
 *   · [put] 只收**成功**的一份（顺手落盘）：一次失败要是被缓存住，接下来十分钟都会"就是读不到"；
 *   · [invalidate] 标脏 —— 写完配置之后活状态就不对了（dsh 还没重载），下次要真去探一次。
 *
 * 线程安全：状态读写都在锁里；[restore] / [loadOnce] 会碰磁盘，调用方保证在 IO 上。
 */
internal object DshLiveInventoryCache {

    /**
     * 缓存多久算过期。
     *
     * 10 分钟是**拍的**，不是量出来的：这份数据的有效期取决于"dsh 有没有换过插件"，而那件事
     * 我们看不见（终端里的 dsh、用户自己改的补丁层都会影响它）。规矩是"过期只触发一次**后台**
     * 更新，不挡渲染"，所以这个数字大小只影响"多久去确认一次"，不影响用户看不看得到东西。
     */
    private const val TTL_MS = 10 * 60 * 1000L

    /** 落盘那份的文件名（放在 `cacheDir` 下；清缓存/清数据会把它一起带走，那是好事）。 */
    internal const val FILE_NAME = "dsh-live-inventory.json"

    private val lock = Any()

    private var snapshot: DshLiveInventory.Ready? = null
    private var storedAtMillis: Long = 0L

    /** 活状态已经和（刚写下去的）配置对不上了 —— 见 [invalidate]。 */
    private var dirty = false

    /** 落盘那份的落点；null = 不落盘（单测里"只测内存"的用法）。 */
    private var storeFile: File? = null

    /** 落盘那份这一辈子只读一次（读过了、读坏了都不再读）。 */
    private var restored = false

    /**
     * 指定落盘目录（调用方给 `context.cacheDir`）。可重复调，幂等。
     *
     * 为什么由调用方给目录、而不是这里自己取 Context：这个对象要在**纯 JVM 单测**里跑（[reset]
     * 就是为它留的），一碰 `android.content.Context` 就只能搬进 Robolectric 了。
     */
    fun attach(directory: File?) {
        synchronized(lock) { storeFile = directory?.let { File(it, FILE_NAME) } }
    }

    /** 上一次探到的活清单；null = 一次都还没探到过（这时才需要真起一次 dsh）。 */
    fun get(): DshLiveInventory.Ready? = synchronized(lock) { snapshot }

    /**
     * 把落盘那份读回内存。**在 IO 上调用**。
     *
     * 时间戳取原文里的 `at`（**不是**"现在"）：那份数据是什么时候拿到的，只有它自己知道 ——
     * 拿现在当时间戳，一个三天前的缓存会显示成"刚更新过"，然后永远不去重探。
     */
    fun restore() {
        val file = synchronized(lock) {
            if (restored) return
            restored = true
            storeFile
        } ?: return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val ready = DshLiveInventoryCodec.parse(text) as? DshLiveInventory.Ready ?: return
        val at = DshLiveInventoryCodec.timestampOf(text)
        synchronized(lock) {
            // 内存里已经有更新的那份就不动它（预热与页面可能同时走到这里）。
            if (snapshot != null) return
            snapshot = ready
            storedAtMillis = at
            dirty = false
        }
    }

    /** 真探的互斥：同一时刻只允许一次 —— 见 [loadOnce] 的说明。 */
    private val probing = Mutex()

    /**
     * 同一时刻只允许一次**真探**，后来者等这一次的结果。
     *
     * 为什么需要：App 起来时会预热一次（`AgentAppRoot`），而用户完全可能在那 ~27 秒里就点开扩展页
     * —— 两边各起一个 dsh 不只是慢一倍：两个进程读同一份 profile，谁也不知道对方在读。
     * 用一把锁把它们并成一次；先到的那次把结果放进缓存，后到的直接拿缓存。
     *
     * [force] = 调用方明确要求"这次必须真探"（扩展页顶部那个刷新按钮）。**不能靠"有没有缓存"来决定
     * 要不要探** —— 那样刷新按钮就只是把旧数据再画一遍（3.0.8.14 及以前就是这个毛病）。
     */
    suspend fun loadOnce(
        force: Boolean = false,
        block: suspend () -> DshLiveInventory,
    ): DshLiveInventory = probing.withLock {
        restore()
        val cached = get()
        if (!force && cached != null && !isStale()) return@withLock cached
        block().also { put(it) }
    }

    /** 存下一次**成功**的探针结果：算作"刚拿到"，并且不再是脏的；顺手落盘。 */
    fun put(value: DshLiveInventory) {
        // 失败不进缓存：那是"这一次没探到"，不是"活清单长这样"。
        if (value !is DshLiveInventory.Ready) return
        synchronized(lock) {
            snapshot = value
            storedAtMillis = System.currentTimeMillis()
            dirty = false
        }
        persist(value)
    }

    /**
     * 落盘。**在 IO 上调用**（[put] 的调用点都在 IO 上）。
     *
     * 写坏了不致命：下一次 [restore] 解析不出来就当没有那份缓存，退回真探 —— 这一页的底线是
     * "不许空白"，不是"缓存必须读得动"。
     */
    private fun persist(value: DshLiveInventory.Ready) {
        val raw = value.raw
        // 没带原文的（单测里手搭的、或桥那份没走解析器）不落盘：写下去也读不回来。
        if (raw.isBlank()) return
        val file = synchronized(lock) { storeFile } ?: return
        runCatching { file.writeText(DshLiveInventoryCodec.encode(raw)) }
    }

    /**
     * 标脏：活状态已经不能代表现状了（最典型的是刚写完一行插件的开关 —— dsh 要下一次对话才
     * 重载，所以活清单里那一行还是旧的）。
     *
     * 标脏**不**清缓存：[get] 照旧把旧的那份给界面渲染（页面不许因此空白），只是让下一次读
     * 真去探一遍。
     */
    fun invalidate() {
        synchronized(lock) { dirty = true }
    }

    /** 过期（超过 [TTL_MS]）或刚被 [invalidate] 标脏；没有缓存时恒为 false（那叫"没有"，不叫"过期"）。 */
    fun isStale(nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        snapshot != null && (dirty || nowMillis - storedAtMillis >= TTL_MS)
    }

    /** 只给单测用：把这份**进程级**状态清干净（含落盘标记），免得一个用例影响下一个。 */
    internal fun reset() {
        synchronized(lock) {
            snapshot = null
            storedAtMillis = 0L
            dirty = false
            storeFile = null
            restored = false
        }
    }
}
