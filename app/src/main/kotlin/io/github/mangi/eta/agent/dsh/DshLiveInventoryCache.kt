package io.github.mangi.eta.agent.dsh

/**
 * 活清单的**进程级**缓存。
 *
 * 为什么需要它：读一次活清单要**真起一次 dsh**（[DshInventoryProbe]）——手机上几秒级，而这份
 * 数据只在"dsh 真的换过插件"之后才会变。进页面、开关一行插件这些动作都不该让它重跑一遍。
 * [DshExtensionsStore] 是页面级对象（`remember {}` 出来的），缓存挂在它身上只能活一次页面，
 * 所以放在这个进程级单例里：页面反复进出，数据还在。
 *
 * 分工：
 *   · [get] 拿快照 —— 页面**先渲染它**：有缓存就绝不起 dsh（先给东西看，再谈准不准）；
 *   · [isStale] 判"过期" —— 超过 [TTL_MS] 或刚被 [invalidate] 标脏。过期**不**妨碍渲染，
 *     只让页面在后台悄悄更新一次，不打回 loading；
 *   · [put] 只收**成功**的一份：一次失败要是被缓存住，接下来十分钟都会"就是读不到"；
 *   · [invalidate] 标脏 —— 写完配置之后活状态就不对了（dsh 还没重载），下次要真去探一次。
 *
 * 线程安全：只在 Compose 的协程里访问（状态读写都在主线程，探针在 IO 里取/存），一把锁就够。
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

    private val lock = Any()

    private var snapshot: DshLiveInventory.Ready? = null
    private var storedAtMillis: Long = 0L

    /** 活状态已经和（刚写下去的）配置对不上了 —— 见 [invalidate]。 */
    private var dirty = false

    /** 上一次探到的活清单；null = 一次都还没探到过（这时才需要真起一次 dsh）。 */
    fun get(): DshLiveInventory.Ready? = synchronized(lock) { snapshot }

    /** 存下一次**成功**的探针结果：算作"刚拿到"，并且不再是脏的。 */
    fun put(value: DshLiveInventory) {
        // 失败不进缓存：那是"这一次没探到"，不是"活清单长这样"。
        if (value !is DshLiveInventory.Ready) return
        synchronized(lock) {
            snapshot = value
            storedAtMillis = System.currentTimeMillis()
            dirty = false
        }
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

    /** 只给单测用：把这份**进程级**状态清干净，免得一个用例影响下一个。 */
    internal fun reset() {
        synchronized(lock) {
            snapshot = null
            storedAtMillis = 0L
            dirty = false
        }
    }
}
