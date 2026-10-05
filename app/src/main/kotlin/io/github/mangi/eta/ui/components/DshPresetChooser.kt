package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshPresetPlane

/**
 * 官方那四个预设的名字；查不到（自定义预设）返回 null，由调用方退回 roster 给的 name。
 *
 * 为什么名字不由 roster 给：官方那四份声明**故意不发布 name**（`dsh-client-ui-agent-preset`
 * 的 `isBuiltInPreset` 注释写着 "A shipped preset publishes no `name`; a declaration that names
 * itself owns its copy"），客户端是拿 id 去查自己的文案表。Heta 照做：id → 资源，查不到才
 * 退回 roster 给的 name（自定义预设走的就是那条）。
 *
 * 扩展页与侧栏那个预设弹窗共用这一份：两处各写一张文案表迟早会漂移（同一个 id 两个名字）。
 */
@Composable
internal fun dshPresetLabel(id: String): String? = when (id) {
    DshPresetPlane.DEFAULT_PRESET -> stringResource(R.string.extensions_preset_standard_name)
    "ptc" -> stringResource(R.string.extensions_preset_ptc_name)
    "minimal" -> stringResource(R.string.extensions_preset_minimal_name)
    // 创造模式：它的 id 就是 cordis（见 DshPresetPlane.CREATOR_PRESET）。
    DshPresetPlane.CREATOR_PRESET -> stringResource(R.string.extensions_preset_cordis_name)
    else -> null
}

/** 官方那四个预设的一句话说明；查不到返回 null（那就只显示名字）。 */
@Composable
internal fun dshPresetSummary(id: String): String? = when (id) {
    DshPresetPlane.DEFAULT_PRESET -> stringResource(R.string.extensions_preset_standard_summary)
    "ptc" -> stringResource(R.string.extensions_preset_ptc_summary)
    "minimal" -> stringResource(R.string.extensions_preset_minimal_summary)
    DshPresetPlane.CREATOR_PRESET -> stringResource(R.string.extensions_preset_cordis_summary)
    else -> null
}

/**
 * 四个官方预设的单选列表。
 *
 * 为什么是一个"列表"而不是顶栏那一行文字：预设是**会话级**的选择（覆盖层每轮重建，下一条消息
 * 生效），不是每条消息都要碰的开关。真机反馈原话是"标准模式在右上角太突兀了"—— 放回侧栏那个
 * 设置区（会话列表左下角那排入口）里最自然，聊天页顶栏只留历史与溢出菜单。
 */
@Composable
internal fun DshPresetChooser(
    selectedId: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DshPresetPlane.PRESET_IDS.forEach { id ->
            EtaRadioButtonPreference(
                title = dshPresetLabel(id) ?: id,
                summary = dshPresetSummary(id),
                selected = id == selectedId,
                onClick = { onSelected(id) },
            )
        }
    }
}
