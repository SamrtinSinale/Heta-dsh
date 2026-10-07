package io.github.mangi.eta.agent.dsh

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「添加插件」内核里那些**纯函数**：输入分类、包名校验、tarball 地址、解包层数。
 *
 * 为什么值得测：这几条决定"用户输入的那一行会被当成什么、去哪儿取"。错一处就是下错东西
 * —— 而且是往运行时里装、以用户权限跑的东西，不能靠手点试出来。
 *
 * 真机取到的事实：客户端那两个源地址是 `https://registry.npmjs.org/` 与
 * `https://registry.npmmirror.com/`（从 `dsh-client-ui-plugin-manager` 源码里核出来的）。
 */
class DshPluginInstallerTest {

    @Test
    fun classifiesPackageNameGithubAndLocalDirectory() {
        assertEquals(
            DshPluginInstaller.Spec.Package("dsh-plugin-whale-pet"),
            DshPluginInstaller.classify("dsh-plugin-whale-pet"),
        )
        assertEquals(
            DshPluginInstaller.Spec.Package("@scope/dsh-plugin-x"),
            DshPluginInstaller.classify("  @scope/dsh-plugin-x  "),
        )

        val github = DshPluginInstaller.classify("https://github.com/author/dsh-plugin")
        assertTrue("$github", github is DshPluginInstaller.Spec.GitHub)
        github as DshPluginInstaller.Spec.GitHub
        assertEquals("author", github.owner)
        assertEquals("dsh-plugin", github.repo)

        // `.git` 后缀与尾斜杠都吃掉
        val withGit = DshPluginInstaller.classify("https://github.com/author/dsh-plugin.git/")
        assertTrue("$withGit", withGit is DshPluginInstaller.Spec.GitHub)
        assertEquals("dsh-plugin", (withGit as DshPluginInstaller.Spec.GitHub).repo)
    }

    @Test
    fun localDirectoryIsOnlyAcceptedWhenItReallyIsADirectory() {
        val directory = Files.createTempDirectory("dsh-plugin-src").toFile()
        try {
            val spec = DshPluginInstaller.classify(directory.absolutePath)
            assertTrue("$spec", spec is DshPluginInstaller.Spec.LocalDirectory)
            assertEquals(directory.name, (spec as DshPluginInstaller.Spec.LocalDirectory).id)
        } finally {
            directory.deleteRecursively()
        }

        // 不存在的路径：不认（宁可报错，也不要装出一个空目录）
        assertNull(DshPluginInstaller.classify("/definitely/not/here"))
    }

    @Test
    fun rejectsNamesThatAreNotPackages() {
        assertTrue(DshPluginInstaller.isPackageName("dsh-plugin-whale-pet"))
        assertTrue(DshPluginInstaller.isPackageName("@deepseek-ai/dsh-status"))
        assertTrue(DshPluginInstaller.isPackageName("a.b_c-d"))
        // 大写、空格、以 . / _ 开头、空 @scope：都不是包名
        assertFalse(DshPluginInstaller.isPackageName("Whale-Pet"))
        assertFalse(DshPluginInstaller.isPackageName("has space"))
        assertFalse(DshPluginInstaller.isPackageName(".hidden"))
        assertFalse(DshPluginInstaller.isPackageName("_private"))
        assertFalse(DshPluginInstaller.isPackageName("@/name"))
        assertFalse(DshPluginInstaller.isPackageName(""))
    }

    @Test
    fun readsTarballUrlAndBuildsSourceUrls() {
        val metadata = "{\"name\":\"x\",\"dist\":{\"tarball\":\"https://registry.npmmirror.com/x/-/x-1.0.0.tgz\"}}"
        assertEquals(
            "https://registry.npmmirror.com/x/-/x-1.0.0.tgz",
            DshPluginInstaller.tarballUrl(metadata),
        )
        assertNull(DshPluginInstaller.tarballUrl("{\"name\":\"x\"}"))
        assertNull(DshPluginInstaller.tarballUrl("这不是 JSON"))

        assertEquals(
            "https://registry.npmjs.org/dsh-plugin-whale-pet",
            DshPluginInstaller.metadataUrl(DshPluginInstaller.Registry.Official, "dsh-plugin-whale-pet"),
        )
        assertEquals(
            "https://registry.npmmirror.com/@scope/x",
            DshPluginInstaller.metadataUrl(DshPluginInstaller.Registry.Npmmirror, "@scope/x"),
        )
        // 两个源地址逐字取自客户端
        assertEquals("https://registry.npmjs.org/", DshPluginInstaller.Registry.Official.base)
        assertEquals("https://registry.npmmirror.com/", DshPluginInstaller.Registry.Npmmirror.base)
        assertEquals("npm 官方源", DshPluginInstaller.Registry.Official.label)
        assertEquals("中国大陆镜像源", DshPluginInstaller.Registry.Npmmirror.label)

        assertEquals(
            "https://codeload.github.com/author/dsh-plugin/tar.gz/refs/heads/main",
            DshPluginInstaller.githubTarballUrl("author", "dsh-plugin"),
        )
        assertEquals(
            "https://codeload.github.com/author/dsh-plugin/tar.gz/refs/heads/master",
            DshPluginInstaller.githubTarballUrl("author", "dsh-plugin", "master"),
        )
    }

    @Test
    fun tarballsAreUnpackedOneLevelDeep() {
        // npm 的 tarball 里套一层 package/，GitHub 的套一层 <repo>-<branch>/ —— 都要剥掉。
        assertEquals(1, DshPluginInstaller.stripComponents(DshPluginInstaller.Spec.Package("x")))
        assertEquals(1, DshPluginInstaller.stripComponents(DshPluginInstaller.Spec.GitHub("x", "a", "b")))
        assertEquals(0, DshPluginInstaller.stripComponents(DshPluginInstaller.Spec.LocalDirectory("x", "/tmp")))
    }
}
