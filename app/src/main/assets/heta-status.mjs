/**
 * 把"只有运行时才知道"的东西写成状态文件，让 App 直接读文件 —— 不再为了看一眼清单再起一个 dsh。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────────────────
 * 扩展页要显示 dsh **活的**插件清单（配置状态 / 运行状态 / 预设），而 ACP 通道没有 remote 出口，
 * 所以 App 只能自己起一个 dsh 把清单算出来（`DshInventoryProbe` + `heta-inventory-bridge.mjs`）。
 * 那条路的代价是**真跑一遍运行时**：chroot + qemu + 挂九十多个插件，实测 ~27 秒，App 每次重启后
 * 第一次点开扩展页都要等这么久。
 *
 * 这个插件挂在**正在跑的那个会话进程**里：会话本来就已经把这些插件挂好了，顺手把同一份投影写成
 * 文件，App 只要 `su -c cat` 读一下（毫秒级）就够了。探针留在原处当兜底（文件不在 / 太旧 / 读不动）。
 *
 * 写出来的东西和桥那行 JSON **同一个形状**（`DshLiveInventoryCodec` 直接就能解析），因为投影是
 * 逐字照抄同一段官方代码（`dsh-host-plugin-inventory` 的 `readPluginInventory`）。
 *
 * 写到 `$DSH_HOME/heta-inventory.json`（真机 `/root/.dsh/heta-inventory.json`）。
 * 为什么带 `at` 时间戳：App 要判断这份是不是太旧了（太旧就退回探针）。
 *
 * ── 什么时候写 ──────────────────────────────────────────────────────────────────
 *   · 挂载后立刻写一次（这是最常见的那次：会话刚起来，App 打开扩展页）；
 *   · 之后**轮询** Loader 条目数 / 配置状态（默认 3 秒一次，很便宜：只数一遍条目），
 *     签名变了就重写 —— 不赌 Loader 的事件名（官方那份 host 插件也是每次现读，不缓存）。
 *
 * config（覆盖层那一行可以给的键，都可以不给）：
 *   intervalMs: number   轮询间隔，默认 3000
 */
import { mkdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

export const name = 'heta-status'

/** 与官方网关同样的注入：只要 loader（`pluginPackages` / `agentPresets` 用 ctx.get 取，缺了不致命）。 */
export const inject = ['loader']

const DSH_HOME = process.env.DSH_HOME ?? '/root/.dsh'
const INVENTORY_FILE = join(DSH_HOME, 'heta-inventory.json')

/**
 * 失败痕迹。
 *
 * 为什么需要它：dsh 的 logger 在 ACP profile 里 info/warn 全被吞掉（stderr 一个字都没有），
 * 而这个插件的 `apply` 又是"尽力而为"（失败不能影响会话）—— 不留痕迹的话，"文件没写出来"
 * 这件事在真机上就完全无从查起。所以：apply 一进来先写一条 stage=apply，publish 抛错写
 * stage=publish + 堆栈，写成功了再把它删掉。
 */
const ERROR_FILE = join(DSH_HOME, 'heta-status-error.txt')

/**
 * 与桥同一行协议前缀（`DshLiveInventoryCodec` 找的就是它）。
 *
 * 为什么连前缀一起写进文件、而不是只写 JSON：这样 App 那边**一个解析器都不用改**（同一段代码
 * 既能解析探针的 stdout，也能解析这个文件），少一处会漂移的地方。
 */
const MARKER = 'HETA-INVENTORY-JSON:'
const DEFAULT_INTERVAL_MS = 3_000

/** 逐字照抄 dsh-host-plugin-inventory 的 FIBER_STATE → phase 映射（含"没有 fiber"这条）。 */
const FIBER_PHASE = {
  0: 'pending',
  1: 'loading',
  2: 'active',
  3: 'failed',
  4: null,
  5: 'unloading',
}

function phaseOf(fiber) {
  if (fiber === undefined) return null
  return FIBER_PHASE[fiber.state] ?? null
}

function phaseOfState(state) {
  if (state === undefined) return null
  return FIBER_PHASE[state] ?? null
}

function patchIdField(value) {
  return typeof value === 'string' && value.length > 0 ? { patchId: value } : {}
}

/**
 * 官方 `PluginLocalizedMeta` → 输出里那个键。
 *
 * 只带 title / description：`icon` 是 base64 data URL，一百多条能把文件顶到几兆，而 App 不画图标。
 */
function metaField(meta) {
  if (meta === undefined || meta === null) return {}
  const out = {}
  if (meta.title !== undefined) out.title = meta.title
  if (meta.description !== undefined) out.description = meta.description
  return Object.keys(out).length === 0 ? {} : { meta: out }
}

function brokenField(value) {
  return typeof value === 'string' && value.length > 0 ? { broken: value } : {}
}

/** 照抄官方 `readPluginInventory` 的条目投影（外加 App 开关要的 `patchId`）。 */
async function readInventory(ctx) {
  const entries = []
  const packages = ctx.get('pluginPackages')
  for (const entry of ctx.loader.entries()) {
    if (entry.options.group) continue
    const base = entry.parent?.tree?.ctx?.baseUrl
    const meta = base === undefined ? undefined : packages?.metaOf(entry.options.name, base)
    entries.push({
      entryId: entry.id,
      moduleName: entry.options.name,
      enabled: !entry.disabled,
      fiberPhase: phaseOf(entry.fiber),
      ...patchIdField(entry.options.id),
      ...metaField(meta),
    })
  }
  const presets = ctx.get('agentPresets')
  if (presets === undefined) return { entries, hasPresets: false, timedOut: false }
  const compositions = await presets.compositionInventory()
  return {
    entries,
    hasPresets: true,
    timedOut: false,
    agentPresets: compositions.map((composition) => ({
      id: composition.id,
      name: composition.name ?? composition.id,
      isDefault: composition.isDefault === true,
      ...brokenField(composition.broken),
      rows: (composition.rows ?? []).map(({ fiberState, ...row }) => ({
        ...row,
        fiberPhase: phaseOfState(fiberState),
        ...patchIdField(row.patchId),
        ...metaField(
          ctx.baseUrl === undefined ? undefined : packages?.metaOf(row.moduleName, ctx.baseUrl),
        ),
      })),
    })),
  }
}

/**
 * 写文件走"先写临时文件再改名"：App 可能正好在读 —— 直接覆盖会读到写了一半的 JSON。
 * 失败只吞掉（这一条路是尽力而为，探针还在）。
 */
function writeJson(file, value) {
  try {
    mkdirSync(dirname(file), { recursive: true })
    const temp = `${file}.tmp`
    writeFileSync(temp, MARKER + JSON.stringify(value) + '\n')
    renameSync(temp, file)
    return true
  } catch {
    return false
  }
}

/** 只看"会不会影响界面"的那几项，用来判断要不要重写（比整份 JSON 便宜得多）。 */
function signatureOf(snapshot) {
  const entries = snapshot.entries
    .map((entry) => `${entry.entryId}:${entry.enabled ? 1 : 0}:${entry.fiberPhase ?? '-'}`)
    .join(',')
  const presets = (snapshot.agentPresets ?? [])
    .map((preset) => `${preset.id}:${preset.rows.length}:${preset.broken ?? '-'}`)
    .join(',')
  return `${entries}|${presets}`
}

export function apply(ctx, config) {
  // 先留一条"我进来了"：连 error 文件都没有 = apply 根本没被调用（插件没挂上 / 模块加载失败）。
  writeJson(ERROR_FILE, { at: Date.now(), stage: 'apply' })
  const intervalMs =
    typeof config?.intervalMs === 'number' && config.intervalMs > 0
      ? config.intervalMs
      : DEFAULT_INTERVAL_MS

  let lastSignature = null
  let inFlight = false

  const publish = async () => {
    if (inFlight) return
    inFlight = true
    try {
      const snapshot = await readInventory(ctx)
      const signature = signatureOf(snapshot)
      if (signature === lastSignature) return
      if (writeJson(INVENTORY_FILE, { at: Date.now(), ...snapshot })) {
        lastSignature = signature
        try {
          rmSync(ERROR_FILE, { force: true })
        } catch {}
      }
    } catch (error) {
      // 读不到就什么都不写：App 那边会退回探针（文件缺失 / 太旧都算"读不到"）。
      // 但**要留证据** —— 否则这条路上出问题只能靠猜（真机上就是这么栽的一次）。
      writeJson(ERROR_FILE, {
        at: Date.now(),
        stage: 'publish',
        error: String((error && error.stack) || error),
      })
      try {
        console.error('[heta-status] publish failed:', error)
      } catch {}
    } finally {
      inFlight = false
    }
  }

  // 挂载后先写一次；之后按签名变化重写。
  void publish()
  const timer = setInterval(() => void publish(), intervalMs)
  if (typeof timer.unref === 'function') timer.unref()

  ctx.on('dispose', () => clearInterval(timer))
}
