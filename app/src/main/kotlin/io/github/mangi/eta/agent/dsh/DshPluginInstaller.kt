package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import io.github.mangi.eta.agent.terminal.RootlessLinuxInstaller
import io.github.mangi.eta.core.SafeTreeDelete
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 「添加插件」：把用户给的那一行（包名 / GitHub 仓库 / 本地目录）装进运行时。
 *
 * 为什么 Heta 自己取包：**运行时里没有 npm/npx/pnpm/yarn**（只有 node，实测），所以客户端那个
 * 「安装源」不可能靠包管理器 —— 它只能自己从源上取 tarball 解包。这里就是那条路：
 *
 *   包名      → 取 `<源><包名>` 的元数据 → `dist.tarball` → 下载 → 解包（剥掉 `package/`）
 *   GitHub    → `codeload.github.com/<owner>/<repo>/tar.gz/refs/heads/main` → 解包（剥掉一层）
 *   本地目录   → 直接复制
 *
 * 三种最后都落到同一处：`<runtime>/opt/dsh/node_modules/<模块名>`，再往 profile 补丁层插一行
 *（`- id: <模块名>` + `name: <模块名>`）—— 与 Heta 自己那两个插件同一套机制，**下一次对话生效**。
 *
 * 安装源与文案逐字取自客户端（`dsh-client-ui-plugin-manager`）：
 * `npm 官方源` = `https://registry.npmjs.org/`、`中国大陆镜像源` = `https://registry.npmmirror.com/`。
 */
internal object DshPluginInstaller {

    private const val TAG = "DshPluginInstaller"

    /** 安装源。地址与客户端源码里那两个常量一字不差。 */
    enum class Registry(val label: String, val base: String) {
        Official("npm 官方源", "https://registry.npmjs.org/"),
        Npmmirror("中国大陆镜像源", "https://registry.npmmirror.com/"),
    }

    /** 用户输入的那一行到底是什么。 */
    sealed interface Spec {
        /** 装完之后的模块名（也是补丁行 id）。 */
        val id: String

        data class Package(override val id: String) : Spec
        data class GitHub(override val id: String, val owner: String, val repo: String) : Spec
        data class LocalDirectory(override val id: String, val path: String) : Spec
    }

    sealed interface Outcome {
        data class Installed(val id: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * 认输入。**纯函数**（好单测）：先看是不是 GitHub 地址，再看是不是本地目录，最后当包名。
     *
     * 为什么这个顺序：`github.com/a/b` 里没有斜杠开头、也不是包名形状；而 `/sdcard/x` 这种以
     * 斜杠开头的必然是目录。剩下的才可能是包名（`@scope/name` 或 `name`）。
     */
    fun classify(raw: String): Spec? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        githubOf(value)?.let { (owner, repo) -> return Spec.GitHub(id = repo, owner = owner, repo = repo) }
        if (value.startsWith("/") || value.startsWith("./") || value.startsWith("file://")) {
            val path = value.removePrefix("file://")
            val name = File(path).name
            if (name.isNotEmpty() && File(path).isDirectory) return Spec.LocalDirectory(id = name, path = path)
            return null
        }
        return if (isPackageName(value)) Spec.Package(id = value) else null
    }

    /** GitHub 地址 → (owner, repo)；不是 GitHub 就 null。`.git` 后缀与尾斜杠都吃掉。 */
    fun githubOf(value: String): Pair<String, String>? {
        val trimmed = value.trim().removeSuffix("/").removeSuffix(".git")
        val marker = "github.com/"
        val index = trimmed.indexOf(marker)
        if (index < 0) return null
        val rest = trimmed.substring(index + marker.length)
        val parts = rest.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1]
        if (owner.isEmpty() || repo.isEmpty()) return null
        return owner to repo
    }

    /**
     * npm 包名合法性（照 npm 那套：小写、数字、`-`、`_`、`.`、可选 `@scope/`）。
     * 不用正则是因为要给出的错误提示要能说清"哪一条不满足"。
     */
    fun isPackageName(value: String): Boolean {
        if (value.isEmpty() || value.length > 214) return false
        if (value.startsWith(".") || value.startsWith("_")) return false
        val body = if (value.startsWith("@")) {
            val slash = value.indexOf('/')
            if (slash <= 1) return false
            value.substring(1, slash) + "/" + value.substring(slash + 1)
        } else {
            value
        }
        return body.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '_' || it == '.' || it == '/' }
    }

    /** registry 元数据里的 tarball 地址（`dist.tarball`）；取不到就 null。 */
    fun tarballUrl(metadataJson: String): String? =
        runCatching { JSONObject(metadataJson).optJSONObject("dist")?.optString("tarball") }
            .getOrNull()
            ?.takeIf { it.startsWith("http") }

    /** GitHub 仓库的 tarball 地址（默认分支用 `main`，取不到时调用方会退到 `master`）。 */
    fun githubTarballUrl(owner: String, repo: String, branch: String = "main"): String =
        "https://codeload.github.com/$owner/$repo/tar.gz/refs/heads/$branch"

    /** registry 里某个包的元数据地址（源地址已带尾斜杠）。 */
    fun metadataUrl(registry: Registry, name: String): String = registry.base + name

    /**
     * 解包时要剥掉几层：npm tarball 里都套一层 `package/`；GitHub 的 tarball 套一层
     * `<repo>-<branch>/`。本地目录不用解包。
     */
    fun stripComponents(spec: Spec): Int = when (spec) {
        is Spec.LocalDirectory -> 0
        else -> 1
    }

    /**
     * 装一个插件。[spec] 是用户输入的那一行；[registry] 只在包名那条路上用得上。
     *
     * 不做"猜"：认不出输入、下载失败、解包失败、包名非法 —— 一律 [Outcome.Failed] 并把原因带回去
     *（界面上原样显示），绝不留半装状态。
     */
    suspend fun install(context: Context, raw: String, registry: Registry): Outcome {
        val spec = classify(raw) ?: return Outcome.Failed("认不出这一行：包名、GitHub 地址或本地目录路径")
        val runtimeRoot = DshRuntimeInstaller.runtimeDirectory(context)
        val modules = File(runtimeRoot, MODULES_RELATIVE)
        return runCatching {
            val moduleName: String = when (spec) {
                is Spec.LocalDirectory -> {
                    val source = File(spec.path)
                    if (!source.isDirectory) error("本地目录不存在：${spec.path}")
                    val name = moduleNameOf(source) ?: error("这个目录里没有可用的 package.json（缺 name）")
                    copyTree(source, File(modules, name))
                    name
                }

                is Spec.GitHub -> {
                    val staging = stagingDirectory(context, spec.repo)
                    val archive = File(staging, "repo.tar.gz")
                    download(githubTarballUrl(spec.owner, spec.repo), archive)
                    val unpacked = File(staging, "unpacked")
                    RootlessLinuxInstaller.extract(archive, unpacked, xz = false, stripComponents = 1)
                    val name = moduleNameOf(unpacked) ?: error("这个仓库里没有可用的 package.json（缺 name）")
                    copyTree(unpacked, File(modules, name))
                    name
                }

                is Spec.Package -> {
                    val staging = stagingDirectory(context, spec.id.substringAfterLast('/'))
                    val metadata = File(staging, "metadata.json")
                    download(metadataUrl(registry, spec.id), metadata)
                    val tarball = tarballUrl(metadata.readText()) ?: error("${registry.label} 上没有这个包：${spec.id}")
                    val archive = File(staging, "package.tgz")
                    download(tarball, archive)
                    val unpacked = File(staging, "unpacked")
                    RootlessLinuxInstaller.extract(archive, unpacked, xz = false, stripComponents = 1)
                    val name = moduleNameOf(unpacked) ?: spec.id
                    copyTree(unpacked, File(modules, name))
                    name
                }
            }
            addPatchRow(context, runtimeRoot, moduleName)
            remember(context, moduleName)
            Outcome.Installed(moduleName)
        }.getOrElse { error ->
            Log.w(TAG, "装插件失败：$raw", error)
            Outcome.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    /** 卸一个插件：删目录 + 删补丁行。删不干净也把原因带回去。 */
    fun uninstall(context: Context, id: String): Outcome = runCatching {
        val runtimeRoot = DshRuntimeInstaller.runtimeDirectory(context)
        val directory = File(File(runtimeRoot, MODULES_RELATIVE), id)
        // 递归删必须走 SafeTreeDelete：插件目录里可能有符号链接（npm 包很常见），裸
        // `deleteRecursively` 会跟着链接删到别处 —— 这是这一摊的不变式之一。
        if (directory.exists() && !SafeTreeDelete.deleteOrRetire(directory)) {
            error("删不掉目录：${directory.absolutePath}")
        }
        DshProfileStore.forRuntime(runtimeRoot).setPluginEnabled(id, id, enabled = false)
        forget(context, id)
        Outcome.Installed(id)
    }.getOrElse { error -> Outcome.Failed(error.message ?: error::class.java.simpleName) }

    /**
     * Heta 自己装过哪些插件（界面只给这些"卸载"入口）。
     *
     * 为什么要有这本账：`node_modules` 里有 169 个随包安装的官方包，删一个 dsh 就废了 —— 界面上
     * 绝不能对任意模块名都能卸。账本放在 runtime 目录里（App 直接读写，不需要 su）。
     */
    fun installed(context: Context): List<String> {
        val file = manifestFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(file.readText())
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    private fun manifestFile(context: Context): File =
        File(DshRuntimeInstaller.runtimeDirectory(context), MANIFEST_RELATIVE)

    private fun remember(context: Context, id: String) {
        writeManifest(context, installed(context).plus(id).distinct().sorted())
    }

    private fun forget(context: Context, id: String) {
        writeManifest(context, installed(context).filterNot { it == id })
    }

    private fun writeManifest(context: Context, ids: List<String>) {
        runCatching {
            val file = manifestFile(context)
            file.parentFile?.mkdirs()
            file.writeText(org.json.JSONArray(ids).toString())
        }.onFailure { Log.w(TAG, "装过哪些插件这本账写不动", it) }
    }

    /** 读一个插件目录的模块名（package.json 的 `name`）。 */
    fun moduleNameOf(directory: File): String? =
        runCatching {
            val manifest = File(directory, "package.json")
            if (!manifest.isFile) return null
            JSONObject(manifest.readText()).optString("name").takeIf { it.isNotBlank() }
        }.getOrNull()

    /** 往 profile 补丁层插一行：`- id: <模块名>` + `name: <模块名>`（下一次对话生效）。 */
    private fun addPatchRow(context: Context, runtimeRoot: File, moduleName: String) {
        val profileDir = DshProfileStore.forRuntime(runtimeRoot).profileDir
        if (!profileDir.canWrite()) DshRuntimeInstaller.handProfileToApp(profileDir)
        DshProfileStore.forRuntime(runtimeRoot).setPluginEnabled(moduleName, moduleName, enabled = true)
    }

    private fun stagingDirectory(context: Context, name: String): File {
        val root = File(context.cacheDir, "dsh-plugin-install")
        root.mkdirs()
        val staging = File(root, name.ifBlank { "plugin" })
        if (staging.exists()) SafeTreeDelete.deleteOrRetire(staging)
        staging.mkdirs()
        return staging
    }

    /** 下载到文件（覆盖写）。失败抛异常，由调用方统一变成 Failed。 */
    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("下载失败：HTTP $code（$url）")
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 复制整棵树（覆盖目标）。 */
    private fun copyTree(source: File, target: File) {
        if (target.exists() && !SafeTreeDelete.deleteOrRetire(target)) {
            error("清不掉旧目录：${target.absolutePath}")
        }
        target.parentFile?.mkdirs()
        if (!source.copyRecursively(target, overwrite = true)) error("复制失败：${source.absolutePath}")
    }

    private const val MODULES_RELATIVE = "opt/dsh/node_modules"
    private const val MANIFEST_RELATIVE = "opt/dsh/heta-installed-plugins.json"
    private const val TIMEOUT_MS = 60_000
    private const val USER_AGENT = "heta-plugin-installer"
}
