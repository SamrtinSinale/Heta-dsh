package io.github.mangi.eta.ui.screens.extensions

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshPluginInstaller
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DshFlatControlsRenderingTest {
    @Test
    fun flatActionsKeepButtonSemanticsAndMinimumTouchTargets() {
        var clicks = 0
        withContent(content = {
            Column {
                DshFlatAction(text = "Add", onClick = { clicks++ })
                DshFlatAction(text = "Disabled", enabled = false, onClick = { clicks++ })
            }
        }) { surface ->
            val enabled = surface.action("Add")
            val disabled = surface.action("Disabled")
            for (node in listOf(enabled, disabled)) {
                assertEquals(Role.Button, node.config[SemanticsProperties.Role])
                assertTrue(node.boundsInRoot.width >= 48f)
                assertTrue(node.boundsInRoot.height >= 48f)
            }
            assertFalse(enabled.config.contains(SemanticsProperties.Disabled))
            assertTrue(disabled.config.contains(SemanticsProperties.Disabled))
            surface.click(enabled)
            surface.click(disabled)
            assertEquals(1, clicks)
        }
    }

    @Test
    fun blankSpecsAreDisabledAndInstallReceivesTrimmedSpecAndSelectedRegistry() {
        val installs = mutableListOf<Pair<String, DshPluginInstaller.Registry>>()
        withContent(content = {
            DshAddPluginContent(working = false, onDismiss = {}, onInstall = { spec, registry ->
                installs += spec to registry
            })
        }) { surface ->
            val installLabel = surface.string(R.string.extensions_add_plugin_install)
            assertTrue(surface.action(installLabel).config.contains(SemanticsProperties.Disabled))
            surface.click(surface.action(installLabel))
            surface.enterText("   ")
            assertTrue(surface.action(installLabel).config.contains(SemanticsProperties.Disabled))
            surface.click(surface.action(installLabel))
            assertTrue(installs.isEmpty())

            val mirror = surface.radio(DshPluginInstaller.Registry.Npmmirror)
            assertTrue(mirror.config[SemanticsProperties.Selected])
            surface.enterText("  dsh-plugin-whale-pet  ")
            surface.click(surface.radio(DshPluginInstaller.Registry.Official))
            assertTrue(surface.radio(DshPluginInstaller.Registry.Official).config[SemanticsProperties.Selected])
            assertFalse(surface.radio(DshPluginInstaller.Registry.Npmmirror).config[SemanticsProperties.Selected])
            assertFalse(surface.action(installLabel).config.contains(SemanticsProperties.Disabled))
            surface.click(surface.action(installLabel))
            assertEquals(listOf("dsh-plugin-whale-pet" to DshPluginInstaller.Registry.Official), installs)
        }
    }

    @Test
    fun workingStateDisablesEditsSelectionAndDialogActions() {
        val working = mutableStateOf(false)
        var installs = 0
        var dismissals = 0
        withContent(content = {
            DshAddPluginContent(
                working = working.value,
                onDismiss = { dismissals++ },
                onInstall = { _, _ -> installs++ },
            )
        }) { surface ->
            surface.enterText("dsh-plugin-whale-pet")
            working.value = true
            surface.refresh()
            assertTrue(surface.field().config.contains(SemanticsProperties.Disabled))
            for (registry in DshPluginInstaller.Registry.entries) {
                assertTrue(surface.radio(registry).config.contains(SemanticsProperties.Disabled))
                surface.click(surface.radio(registry))
            }
            for (label in listOf(
                surface.string(R.string.action_cancel),
                surface.string(R.string.extensions_add_plugin_install),
            )) {
                assertTrue(surface.action(label).config.contains(SemanticsProperties.Disabled))
                surface.click(surface.action(label))
            }
            assertTrue(surface.radio(DshPluginInstaller.Registry.Npmmirror).config[SemanticsProperties.Selected])
            assertEquals(0, installs)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun expandedGuideKeepsActionsVisibleAtLargeFontAndConstrainedHeight() {
        withContent(height = 420, fontScale = 1.6f, content = {
            DshAddPluginContent(working = false, onDismiss = {}, onInstall = { _, _ -> })
        }) { surface ->
            val installLabel = surface.string(R.string.extensions_add_plugin_install)
            val before = surface.action(installLabel).boundsInRoot
            surface.click(surface.action(surface.string(R.string.extensions_add_plugin_guide)))
            for (label in listOf(surface.string(R.string.action_cancel), installLabel)) {
                val bounds = surface.action(label).boundsInRoot
                assertTrue("Action must remain visible: $label", bounds.height >= 48f)
                assertTrue(bounds.top >= 0f)
                assertTrue(bounds.bottom <= surface.view.height.toFloat())
                assertTrue(bounds.width >= 48f)
            }
            val after = surface.action(installLabel).boundsInRoot
            assertEquals(before.bottom, after.bottom, 0.5f)
            assertTrue(surface.nodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any {
                    it.text == surface.string(R.string.extensions_add_plugin_guide_package)
                } == true
            })
        }
    }

    private class Surface(val view: ComposeView, private val height: Int) {
        fun string(id: Int): String = view.context.getString(id)

        fun refresh() {
            // Let recomposition and the following measure/layout pass settle after each action.
            repeat(2) {
                ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
                )
                view.layout(0, 0, 320, view.measuredHeight)
            }
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }

        fun nodes(): List<SemanticsNode> {
            val ownerView = view.getChildAt(0)
            val owner = ownerView.javaClass.getMethod("getSemanticsOwner").invoke(ownerView) as SemanticsOwner
            return descendants(owner.unmergedRootSemanticsNode)
        }

        fun action(label: String): SemanticsNode = nodes().first { node ->
            node.config.contains(SemanticsActions.OnClick) && descendants(node).any { child ->
                child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            }
        }

        fun radio(registry: DshPluginInstaller.Registry): SemanticsNode = action(registry.label).also {
            assertEquals(Role.RadioButton, it.config[SemanticsProperties.Role])
        }

        fun field(): SemanticsNode = nodes().first { it.config.contains(SemanticsProperties.EditableText) }

        fun enterText(text: String) {
            assertTrue(requireNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(text)))
            refresh()
        }

        fun click(node: SemanticsNode) {
            requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            refresh()
        }

        private fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
    }

    private fun withContent(
        height: Int = 1000,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        verify: (Surface) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = ComposeView(controller.get())
        try {
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MiuixTheme { content() }
                }
            }
            val surface = Surface(view, height)
            surface.refresh()
            verify(surface)
        } finally {
            view.disposeComposition()
            controller.pause().stop().destroy()
        }
    }
}
