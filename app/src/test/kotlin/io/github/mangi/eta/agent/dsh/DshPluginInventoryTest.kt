package io.github.mangi.eta.agent.dsh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `DshPluginInventory` 的单测（纯 JVM：只读文件，不需要 Robolectric）。
 *
 * 夹具是**按真文件的样子**搭的：`dsh-base` 的那份 insert 清单（含 `disabled: !!js "…"`）、
 * `dsh-acp-app` 的那份覆盖清单、以及 profile 模板自带的注释 + `[]`。
 *
 * 这些用例钉的是**合并规则**（谁覆盖谁、什么字段不该被覆盖、顺序怎么来），因为那是我从 dsh
 * 源码里抄的、最可能抄错的部分。真机上的最终对照是 CI 里那条 `--dump-config` 交叉验证。
 */
class DshPluginInventoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File

    private val installationModules get() = File(root, "opt/dsh/node_modules")
    private val profileDir get() = File(root, "root/.dsh/profiles/acp")

    @Test
    fun matchesTheRuntimesOwnComposedTree() {
        // **交叉验证**：先跑一次 dsh 自己的 `--dump-config`（真实现成的组合结果），把它的输出与
        // 运行时的根目录用环境变量指过来，然后把两边的行逐条对比。
        //
        // 为什么非要有这条：单测只能证明"我抄得自洽"，证明不了"我抄对了"。这套层序/合并规则是
        // 从 dsh 源码里读出来的，只有和它自己算出来的东西对上，才算数。CI 里由
        // scripts/test-dsh-inventory.sh 准备这两个变量；没给就跳过（本地默认跳）。
        val dumpPath = System.getenv("HETA_DUMP_YAML")
        val rootPath = System.getenv("HETA_DUMP_ROOT")
        assumeTrue(
            "没给 HETA_DUMP_YAML / HETA_DUMP_ROOT：跳过交叉验证",
            !dumpPath.isNullOrBlank() && !rootPath.isNullOrBlank(),
        )

        val expected = DshPatchDocument.parse(File(dumpPath).readText()).rows
        val actual = DshPluginInventory(File(rootPath)).read().rows

        assertTrue("dsh 自己吐出来的行不该是空的", expected.isNotEmpty())
        assertEquals(
            "两边的行必须逐条一致（id / 模块名 / 开关状态，顺序也算）",
            expected.map { Triple(it.patchId, it.moduleName, it.state) },
            actual.map { Triple(it.patchId, it.moduleName, it.state) },
        )
    }

    @Test
    fun aSingleStringPatchDeclarationIsReadLikeTheRealBundles() {
        // 真 bundle 写的就是字符串形式（`"patch": "./cordis.patch.yml"`）。
        runtime(bundles = listOf(BASE))
        val dir = File(installationModules, BASE)
        dir.mkdirs()
        write(
            File(dir, "package.json"),
            """{ "name": "$BASE", "dsh": { "bundle": { "patch": "./cordis.patch.yml" } } }""",
        )
        write(File(dir, "cordis.patch.yml"), "- id: from-string-form\n")

        assertEquals(listOf("from-string-form"), DshPluginInventory(root).read().rows.map { it.patchId })
    }

    @Test
    fun listsRowsFromEverySelectedBundleInFirstAppearanceOrder() {
        runtime()
        installBundle(BASE, BASE_PATCH)
        installBundle(ACP_APP, ACP_APP_PATCH)

        val inventory = DshPluginInventory(root).read()

        assertEquals(
            listOf(
                "tool-plugin-manager",
                "plugin-manager",
                "hmr",
                "session-title",
                "system-prompt",
                "session-title-llm",
                "acp-app-startup",
            ),
            inventory.rows.map { it.patchId },
        )
        assertEquals(
            listOf(
                DshRowState.DISABLED,
                DshRowState.UNRESOLVED,
                DshRowState.UNRESOLVED,
                DshRowState.ENABLED,
                DshRowState.ENABLED,
                DshRowState.DISABLED,
                DshRowState.ENABLED,
            ),
            inventory.rows.map { it.state },
        )
        assertEquals(
            "行要能说清自己是哪个 bundle 带进来的",
            listOf(BASE, BASE, BASE, BASE, ACP_APP, ACP_APP, ACP_APP),
            inventory.rows.map { it.source },
        )
        assertTrue("`insert` 分组本身不是插件行", inventory.rows.none { it.patchId == "insert" })
        assertTrue(inventory.problems.isEmpty())
    }

    @Test
    fun profileLayerOverridesTheStateButKeepsTheDeclaredName() {
        runtime(profilePatch = template("- id: hmr\n  disabled: false\n"))
        installBundle(BASE, BASE_PATCH)
        installBundle(ACP_APP, ACP_APP_PATCH)

        val hmr = DshPluginInventory(root).read().rows.single { it.patchId == "hmr" }

        assertEquals(DshRowState.ENABLED, hmr.state)
        assertEquals(
            "覆盖行不写 name，声明层的模块名必须留着（不然界面就没法按模块名定位了）",
            "@deepseek-ai/dsh-hmr",
            hmr.moduleName,
        )
        assertEquals(BASE, hmr.source)
        assertEquals(listOf("profile 补丁层"), hmr.mentionedBy)
    }

    @Test
    fun aLaterLayerThatOnlyMentionsOtherKeysDoesNotTouchTheState() {
        // dsh 的补丁是逐字段盖的：后一层只写 config 的行**不能**把前一层的 disabled 抹掉。
        runtime(profilePatch = template("- id: plugin-manager\n  config:\n    probe: 1\n"))
        installBundle(BASE, BASE_PATCH)

        val row = DshPluginInventory(root).read().rows.single { it.patchId == "plugin-manager" }

        assertEquals(DshRowState.UNRESOLVED, row.state)
        assertEquals(
            "只提了 config 也算「提到过」（config 的变化本类看不到，口径宁宽勿假）",
            listOf("profile 补丁层"),
            row.mentionedBy,
        )
    }

    @Test
    fun homeLayerAppliesAfterTheProfileAndTheOverlayAppliesLast() {
        runtime(
            profilePatch = template("- id: hmr\n  disabled: false\n"),
            homePatch = "- id: hmr\n  disabled: true\n",
            overlay = "- id: hmr\n  disabled: false\n",
        )
        installBundle(BASE, BASE_PATCH)

        val hmr = DshPluginInventory(root).read().rows.single { it.patchId == "hmr" }

        assertEquals("最后写的那层说了算（我们自己的覆盖层排最后）", DshRowState.ENABLED, hmr.state)
        assertEquals(listOf("profile 补丁层", "home 补丁层", "Heta 覆盖层"), hmr.mentionedBy)
    }

    @Test
    fun unselectedBundlesContributeNoRowsButAreStillListed() {
        runtime(bundles = listOf(BASE))
        installBundle(BASE, BASE_PATCH)
        installBundle(ACP_APP, ACP_APP_PATCH)

        val inventory = DshPluginInventory(root).read()

        assertTrue("没选中的 bundle 不该往表里带行", inventory.rows.none { it.source == ACP_APP })
        val listed = inventory.bundles.single { it.name == ACP_APP }
        assertFalse(listed.selected)
        assertTrue("没选中的也要能被看见、并说明它确实是个 bundle", listed.isBundle)
        assertEquals(3, listed.rowCount)
    }

    @Test
    fun nonBundleDependenciesAreHiddenUnlessSelected() {
        // 随包运行时的安装依赖里 82 个只有 2 个是 bundle，其余全是插件包：没选中的不能全列出来，
        // 不然「扩展」页面会被"它不是 bundle"糊满（官方 listBundles 也是这么跳过的）。
        runtime(bundles = listOf(BASE), installationDependencies = listOf(BASE, "@deepseek-ai/dsh-plain"))
        installBundle(BASE, BASE_PATCH)
        installBundle("@deepseek-ai/dsh-plain", content = null)

        assertEquals(listOf(BASE), DshPluginInventory(root).read().bundles.map { it.name })
    }

    @Test
    fun aSelectedPackageThatIsNotInstalledIsReportedWithAReason() {
        runtime(bundles = listOf(BASE, "@deepseek-ai/dsh-not-installed"))
        installBundle(BASE, BASE_PATCH)

        val inventory = DshPluginInventory(root).read()

        val missing = inventory.bundles.single { it.name == "@deepseek-ai/dsh-not-installed" }
        assertFalse(missing.isBundle)
        assertNotNull("读不出来必须给原因", missing.problem)
        assertTrue("别的 bundle 该照常列出来", inventory.rows.isNotEmpty())
    }

    @Test
    fun aPackageWithoutBundleMetadataIsReported() {
        runtime(bundles = listOf(BASE, "@deepseek-ai/dsh-plain"))
        installBundle(BASE, BASE_PATCH)
        installBundle("@deepseek-ai/dsh-plain", content = null)

        val plain = DshPluginInventory(root).read().bundles.single { it.name == "@deepseek-ai/dsh-plain" }

        assertFalse(plain.isBundle)
        assertTrue(plain.problem!!.contains("dsh.bundle.patch"))
    }

    @Test
    fun anUnreadablePatchFileSkipsTheWholeBundle() {
        runtime(bundles = listOf(BASE, ACP_APP))
        installBundle(BASE, BASE_PATCH)
        installBundle(ACP_APP, content = "- id: x\n", declare = listOf("missing.yml"))

        val inventory = DshPluginInventory(root).read()

        val skipped = inventory.bundles.single { it.name == ACP_APP }
        assertTrue("一处读不出来就整层跳过（同官方 loadProfileDirectory）", skipped.problem!!.contains("missing.yml"))
        assertTrue(inventory.rows.none { it.source == ACP_APP })
    }

    @Test
    fun aBundlesPatchListIsReadInOrder() {
        runtime(bundles = listOf(BASE))
        installBundle(
            BASE,
            content = null,
            declare = listOf("first.yml", "second.yml"),
            files = mapOf("first.yml" to "- id: from-first\n", "second.yml" to "- id: from-second\n"),
        )

        assertEquals(
            listOf("from-first", "from-second"),
            DshPluginInventory(root).read().rows.map { it.patchId },
        )
    }

    @Test
    fun theInstallationCopyWinsOverAProfileLocalCopy() {
        // 官方契约：自带的 bundle 永远来自和 dsh 同一份安装，不会用 profile 里的副本。
        runtime()
        installBundle(BASE, "- id: from-installation\n")
        installBundle(BASE, "- id: from-profile\n", where = File(profileDir, "node_modules"))

        val inventory = DshPluginInventory(root).read()

        assertEquals(listOf("from-installation"), inventory.rows.map { it.patchId })
    }

    @Test
    fun aHoistedNodeModulesAboveTheProfileIsFound() {
        runtime()
        installBundle(BASE, "- id: hoisted\n", where = File(root, "root/.dsh/profiles/node_modules"))

        assertEquals(listOf("hoisted"), DshPluginInventory(root).read().rows.map { it.patchId })
    }

    @Test
    fun theCatalogueIsSelectedFirstThenProfileThenInstallation() {
        runtime(
            bundles = listOf(BASE, ACP_APP),
            profileDependencies = listOf("@deepseek-ai/dsh-extra"),
        )
        installBundle(BASE, BASE_PATCH)
        installBundle(ACP_APP, ACP_APP_PATCH)
        installBundle("@deepseek-ai/dsh-extra", "- id: extra\n")
        installBundle(REVIEW, "- id: review\n")

        val names = DshPluginInventory(root).read().bundles.map { it.name }

        assertEquals(listOf(BASE, ACP_APP, "@deepseek-ai/dsh-extra", REVIEW), names)
    }

    @Test
    fun anEmptyProfileWithNoPatchLayerStillListsTheBundleRows() {
        runtime(profilePatch = null)
        installBundle(BASE, "- id: only-row\n")

        val inventory = DshPluginInventory(root).read()

        assertEquals(listOf("only-row"), inventory.rows.map { it.patchId })
        assertTrue(inventory.problems.isEmpty())
        assertNull(inventory.bundles.single { it.name == BASE }.problem)
    }

    /** 搭一棵 runtime 树：安装清单 + profile 清单（+ 可选的各层补丁文件）。 */
    private fun runtime(
        bundles: List<String> = listOf(BASE, ACP_APP),
        profilePatch: String? = template(),
        homePatch: String? = null,
        overlay: String? = null,
        profileDependencies: List<String> = emptyList(),
        installationDependencies: List<String> = listOf(BASE, ACP_APP, REVIEW),
    ) {
        root = temporaryFolder.newFolder("runtime")
        profileDir.mkdirs()
        File(root, "opt/dsh").mkdirs()

        write(
            File(root, "opt/dsh/package.json"),
            manifest("dsh", installationDependencies),
        )
        write(
            File(profileDir, "package.json"),
            manifest("dsh-profile-acp", profileDependencies, bundles),
        )
        profilePatch?.let { write(File(profileDir, "cordis.patch.yml"), it) }
        homePatch?.let { write(File(root, "root/.dsh/cordis.patch.yml"), it) }
        overlay?.let { write(File(root, "opt/dsh/heta-run-overlay.patch.yml"), it) }
    }

    /**
     * 装一个包。
     *
     * [content] 是那个默认补丁文件的内容；`null` = **不声明** `dsh.bundle`（测"这不是 bundle"）。
     * [declare] 覆盖它声明的文件名列表（可以指向不存在的文件，用来测"读不出来"）。
     * [files] 直接给多个补丁文件的内容。
     */
    private fun installBundle(
        name: String,
        content: String? = "- id: x\n",
        where: File = installationModules,
        declare: List<String>? = null,
        files: Map<String, String> = emptyMap(),
    ) {
        val dir = File(where, name)
        dir.mkdirs()
        val declared = declare ?: if (content == null) null else listOf("cordis.patch.yml")
        val bundle = if (declared == null) "" else ""","dsh": { "bundle": { "patch": ${quoted(declared)} } }"""
        write(
            File(dir, "package.json"),
            """{ "name": "$name", "version": "1.0.0"$bundle }""",
        )
        val contents = when {
            files.isNotEmpty() -> files
            content != null -> mapOf("cordis.patch.yml" to content)
            else -> emptyMap()
        }
        for ((file, text) in contents) write(File(dir, file), text)
    }

    private fun manifest(name: String, dependencies: List<String>, bundles: List<String>? = null): String {
        val deps = dependencies.joinToString(", ") { "\"$it\": \"1.0.0\"" }
        val profile = if (bundles == null) "" else ""","dsh": { "profile": { "bundles": ${quoted(bundles)} } }"""
        return """{ "name": "$name", "private": true, "dependencies": { $deps }$profile }"""
    }

    private fun quoted(values: List<String>): String = values.joinToString(", ", "[", "]") { "\"$it\"" }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private companion object {
        const val BASE = "@deepseek-ai/dsh-base"
        const val ACP_APP = "@deepseek-ai/dsh-acp-app"
        const val REVIEW = "@deepseek-ai/dsh-experimental-auto-review"

        /** `dsh-base/cordis.patch.yml` 的形状（含 `!!js`，真文件里就是这样）。 */
        val BASE_PATCH = """
            # The dsh-base bundle patch: the shared core of each base-backed profile.

            - insert:
                - id: tool-plugin-manager
                  name: '@deepseek-ai/dsh-plugin-manager/tools'
                  disabled: true

                - id: plugin-manager
                  name: '@deepseek-ai/dsh-plugin-manager'
                  disabled: !!js "!ctx.get('profileContext')"

                - id: hmr
                  name: '@deepseek-ai/dsh-hmr'
                  disabled: !!js "!ctx.get('profileContext')"
                  config:
                    root: []

                - id: session-title
                  name: '@deepseek-ai/dsh-session-title'
                  config:
                    fallbackMaxWords: 5
        """.trimIndent() + "\n"

        /** `dsh-acp-app/cordis.patch.yml` 的形状。 */
        val ACP_APP_PATCH = """
            # The automation-only ACP application over dsh-base. Stdout belongs to ACP.

            - id: system-prompt
              config:
                personaPrefix: >-
                  You are a coding agent powered by the {{model}} model.

            - id: session-title-llm
              disabled: true

            - insert:
                - id: acp-app-startup
                  name: '@deepseek-ai/dsh-acp-app'
        """.trimIndent() + "\n"
    }
}

/**
 * profile 模板自带的注释（逐字抄自 dsh 的 `PROFILE_PATCH_TEMPLATE`），后面接 [rows]；没有条目时
 * 就是空的 `[]`。
 *
 * 夹具不能写成"先 `[]` 再追加条目"：一个 YAML 文档不能有两个根节点 —— dsh 读不了，我们自己的
 * 解析器也会拒绝（这正是它该干的事，第一次跑单测就是被这个夹具坑了）。
 */
private fun template(vararg rows: String): String {
    val header = "# Your patch layer for this dsh profile, applied after every bundle layer:\n" +
        "# a top-level YAML array of loader patch entries (id-targeted config\n" +
        "# overrides, disables, and insert lists; `!!js` expressions allowed).\n"
    val body = if (rows.isEmpty()) "[]" else rows.joinToString("").trimEnd('\n')
    return header + body + "\n"
}
