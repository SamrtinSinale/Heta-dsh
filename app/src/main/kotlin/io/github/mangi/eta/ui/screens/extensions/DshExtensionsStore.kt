package io.github.mangi.eta.ui.screens.extensions

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshInventory
import io.github.mangi.eta.agent.dsh.DshInventoryRow
import io.github.mangi.eta.agent.dsh.DshPluginInventory
import io.github.mangi.eta.agent.dsh.DshProfileStore
import io.github.mangi.eta.agent.dsh.DshProfileWrite
import io.github.mangi.eta.agent.dsh.DshRuntimeInstaller
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
 * 读与写共用**同一个** busy 标志：两者碰的是同一份 profile 文件，重叠执行时读回来的可能是
 * 写之前的形态，列表就会和刚点出来的结果对不上。
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

    /** 读或写在飞：期间禁掉开关与刷新，免得两次写互相盖掉。 */
    var working by mutableStateOf(false)
        private set

    /** null = 还没读到（首次加载中，或读失败）。 */
    var inventory by mutableStateOf<DshInventory?>(null)
        private set

    /** 结果提示；写入被拒绝时它就是数据层给的理由原文。 */
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set

    init {
        reload()
    }

    /** 重新读清单：首次进入、点刷新、以及真正写成功之后各一次。 */
    fun reload() {
        if (working) return
        working = true
        scope.launch {
            try {
                val ready = withContext(Dispatchers.IO) { DshRuntimeInstaller.isReady(appContext) }
                runtimeReady = ready
                if (!ready) {
                    // 运行时不在时读出来只会是一屏"找不到这个包"：那不是这个页面要说的事。
                    inventory = null
                    return@launch
                }
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
                        DshPluginInventory(runtimeRoot).read()
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
            } finally {
                working = false
                loading = false
            }
        }
    }

    /** 选 / 禁一个 bundle：改 profile 清单里的 `dsh.profile.bundles`。 */
    fun setBundleSelected(name: String, selected: Boolean) {
        write { profile -> profile.setBundleSelected(name, selected) }
    }

    /** 开 / 关一行插件：按补丁行 id 往 profile 补丁层写该行的 `disabled`。 */
    fun setPluginEnabled(row: DshInventoryRow, enabled: Boolean) {
        write { profile -> profile.setPluginEnabled(row.patchId, row.moduleName, enabled) }
    }

    private fun write(action: (DshProfileStore) -> DshProfileWrite) {
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
                onSuccess = ::settle,
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    fail(R.string.extensions_write_failed, error)
                },
            )
        }
    }

    private fun settle(result: DshProfileWrite) {
        when (result) {
            is DshProfileWrite.Ok -> if (result.changed) {
                message = appContext.getString(R.string.extensions_message_saved)
                messageIsError = false
                // 只有真动了文件才重读：changed=false 时清单不可能变。
                reload()
            } else {
                message = appContext.getString(R.string.extensions_message_unchanged)
                messageIsError = false
            }

            // 拒绝的理由只在这里能看到，原样显示：它是数据层的中文文案，包一层反而盖住了原因。
            is DshProfileWrite.Rejected -> {
                message = result.reason
                messageIsError = true
            }
        }
    }

    private fun fail(resourceId: Int, error: Throwable) {
        message = appContext.getString(resourceId, error.message ?: error::class.java.simpleName)
        messageIsError = true
    }
}
