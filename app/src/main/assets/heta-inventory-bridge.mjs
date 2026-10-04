/**
 * Heta 的「插件清单出口」：把 dsh **活的** Loader 条目打成一行 JSON，交给 App 的「扩展」页面。
 *
 * 为什么需要它：官方客户端那一页（配置状态 / 运行状态 / 预设）的数据来自 dsh 进程内的
 * `readPluginInventory`（`@deepseek-ai/dsh-host-plugin-inventory`，其 lib/index.js 第 122-158 行），
 * 而 **ACP 通道没有 remote 出口**（随包的 dsh-acp 里 `typert` / `remote` / `pluginInventory`
 * 零命中）。所以让 dsh 自己按 dsh 的算法算一遍、把结果打出来，是 App 拿到同一份数据的唯一办法。
 *
 * 条目投影**照抄**那份 `readPluginInventory`：跳过 group 条目，取 `entry.id` /
 * `entry.options.name` / `!entry.disabled` / `entry.fiber.state`；Fiber 状态映射表
 * （0..5 → pending/loading/active/failed/null/unloading）也逐字照抄，`entry.fiber === undefined`
 * 时 phase 为 null（官方那行三元就是 null，映射表里的 null 是 DISPOSED=4）。官方还会带
 * `pluginPackages` 的显示名元数据，这里省掉 —— App 侧不用它，多带一份就要多维护一份。
 *
 * 每个条目**多带一个 `patchId`**（= `entry.options.id`），预设行尽量透传同名键。
 *
 * 为什么需要它：App 上的开关是**写 profile 补丁层**，而写文件是按**补丁行 id**定位那一行的
 * （官方 `writePluginEnabled(path, row.patchId, row.moduleName, enabled)`）；这里透出的
 * `entryId` 是 **Loader 树里的 id**（`include:acp` 这种带 include 前缀的），**两者不是一回事**。
 * 官方 `listPlugins` 正是自己补上这一跳：拿 `entry.entryId` 回 Loader 树里找出那个条目、再取
 * `entry.options.id` 当 `patchId`（`const candidates = rows.filter((row) => row.id ===
 * actual?.options.id)`）—— App 侧拿不到 Loader 树，所以这一跳只能在这里做。
 *
 * `options.id` 缺失时**不写这个键**：少一个键就是"这一行没有补丁行 id，改不了"；写 null
 * 反而会被 App 读成"有值但为空"，把"定位不到"伪装成"定位到了"。
 *
 * 什么时候打：dsh 的"就绪"是**服务式**的（`dsh-acp-app` 用 `ctx.provide('acpAppStartup')`
 * 让 ACP 桥等它），并没有一个全局 ready 事件可以赌。所以这里用**稳定判据**：每 250ms 数一次
 * Loader 条目，连续 1s 不再增长就认为挂完了；`ready` 事件若真的来，就立刻打。
 *
 * 输出协议（给 App 读，尽量笨）：**只有一行**带 `HETA-INVENTORY-JSON:` 前缀的 JSON，
 * 其余输出（dsh 自己的日志/警告）留在 stderr，不会和 JSON 混在一起。
 *
 * 退出码：0 = 清单完整；3 = 超时（把当时看到的打出来，别让调用方干等）。
 *
 * 实测（2026-10-04，真 arm64 运行时 + qemu-aarch64）：acp profile 是 98 条非 group 条目、
 * `hasPresets: false`（随包没有 roster 发布者）、无重复 entryId，整条脚本 27 秒。
 * 带上 `patchId` 之后复测（同一条命令，本机 20 秒）：98 条**全部**有它，头三条是
 * `include` → `include`、`include:tool-plugin-manager` → `tool-plugin-manager`、
 * `include:plugin-manager` → `plugin-manager`。这一份运行时里恰好每条都只是"include: 一层"，
 * 所以"entryId 去掉前缀"那个老经验这次也对得上 —— 但那是巧合，不是契约：include 可以嵌套，
 * Loader 树 id 与补丁行 id 本来就不是一个空间，所以补丁行 id 必须由桥自己给（见上面那段）。
 */

const MARKER = 'HETA-INVENTORY-JSON:'
const POLL_MS = 250
const STABLE_MS = 1_000
/**
 * 等多久算超时。
 *
 * 90 秒是**量出来的**：在真 arm64 运行时 + qemu-aarch64（宿主 binfmt，flags POF）上，
 * 整条探针脚本从进 chroot 到打出 JSON 是 27 秒；而桥的计时从它被挂上就开始了，也就是
 * 说这里能拿到的余量本来就不多。原来的 30 秒在更慢的 CI 机器上会直接踩线 —— 那时桥会打
 * 一份 timedOut 快照，App 侧按"不完整"拒收（见 DshLiveInventoryCodec），于是页面退回文件视图。
 * 与其让它在慢机器上退化成那样，不如把窗口放到 3 倍。
 *
 * 上限由调用方兜着：`DshInventoryProbe` 的 waitFor 比这里长，桥自己超时会先打一份快照再退。
 */
const TIMEOUT_MS = 90_000
/** 兜底退出：stdout 回调万一没被调用，也不能把上层进程挂在这儿。 */
const EXIT_FALLBACK_MS = 2_000

/** 逐字照抄 dsh-host-plugin-inventory 的 FIBER_STATE → phase 映射（含"没有 fiber"这条）。 */
const FIBER_PHASE = {
  0: 'pending',
  1: 'loading',
  2: 'active',
  3: 'failed',
  4: null,
  5: 'unloading',
}

export const name = 'heta-inventory-bridge'

/** 与官方网关同样的注入：只要 loader。 */
export const inject = ['loader']

/** Loader 条目：拿 `entry.fiber.state`；没有 fiber（未挂载/已摘掉）就是 null。 */
function phaseOf(fiber) {
  if (fiber === undefined) return null
  return FIBER_PHASE[fiber.state] ?? null
}

/** 预设行：状态是**行自己的** `fiberState` 字段（不是 fiber 对象），缺失同样算 null。 */
function phaseOfState(state) {
  if (state === undefined) return null
  return FIBER_PHASE[state] ?? null
}

/**
 * 补丁行 id → 输出里那个键。
 *
 * 只在**非空字符串**时才带出去（缺失 / 不是字符串 / 空串一律不带这个键，理由见文件头）：
 * 条目走 `entry.options.id`，预设行走行自己的 `patchId`。
 */
function patchIdField(value) {
  return typeof value === 'string' && value.length > 0 ? { patchId: value } : {}
}

/** 照抄官方 `readPluginInventory` 的条目投影与预设投影（外加 App 开关要的 `patchId`）。 */
async function readInventory(ctx) {
  const entries = []
  for (const entry of ctx.loader.entries()) {
    if (entry.options.group) continue
    entries.push({
      entryId: entry.id,
      moduleName: entry.options.name,
      enabled: !entry.disabled,
      fiberPhase: phaseOf(entry.fiber),
      ...patchIdField(entry.options.id),
    })
  }
  const presets = ctx.get('agentPresets')
  if (presets === undefined) return { entries, hasPresets: false }
  return { entries, agentPresets: await readPresets(presets), hasPresets: true }
}

/**
 * 预设那半边：有 roster 才走得到（随包运行时里 `compositionInventory` 只出现在
 * dsh-host-plugin-inventory 自己的调用点上，发布者不在 ⇒ `agentPresets` 服务不存在）。
 *
 * 行**原样透出**：官方那边行里的 `enabled` 可能是布尔，也可能是字符串 `'conditional'`
 * （带 `condition` 说明由谁决定）。这里不翻译、不归一 —— 翻译是 App 侧显示层的事，
 * 数据层擅自改写会让"到底哪种"无从查证。
 */
async function readPresets(presets) {
  const compositions = await presets.compositionInventory()
  return compositions.map((composition) => ({
    id: composition.id,
    name: composition.name ?? composition.id,
    isDefault: composition.isDefault === true,
    rows: (composition.rows ?? []).map(({ fiberState, ...row }) => ({
      ...row,
      fiberPhase: phaseOfState(fiberState),
      // 预设行**只透传**：官方投影的 AgentPresetPluginRow 里没有补丁行 id 这个字段
      //（只有 entryId / moduleName / enabled / condition / fiberPhase），官方那边
      // listPlugins 也只覆盖 Loader 条目，预设组合行不参与写文件。行自己带了 `patchId`
      // 就带出去，没带就不写这个键 —— 绝不拿 `entryId` 冒充它：那是组合行自己的 id，
      // 写进 profile 补丁层就是往错的行上写。
      ...patchIdField(row.patchId),
    })),
  }))
}

export function apply(ctx) {
  let done = false
  let timer = null

  const finish = async (timedOut) => {
    if (done) return
    done = true
    if (timer !== null) clearInterval(timer)
    let snapshot
    try {
      snapshot = await readInventory(ctx)
    } catch (error) {
      // 清单读不出来也要给一行 JSON：调用方靠 marker 判定成败，一句可读的原因比空 stdout 有用。
      snapshot = {
        entries: [],
        error: String(error && error.message ? error.message : error),
      }
    }
    const line = `${MARKER}${JSON.stringify({ ...snapshot, timedOut: timedOut === true })}\n`
    // 回调里再退出：不然 stdout 可能还没刷出去就没了。
    process.stdout.write(line, () => process.exit(timedOut === true ? 3 : 0))
    setTimeout(() => process.exit(timedOut === true ? 3 : 0), EXIT_FALLBACK_MS).unref?.()
  }

  // 稳定判据：条目数连续 STABLE_MS 不变就认为挂完了（失败的行也计入 —— 挂在 failed 上的条目
  // 同样是"已经定下来了"，等它变 active 是空等）。
  let lastCount = -1
  let stableSince = Date.now()
  const startedAt = Date.now()
  timer = setInterval(() => {
    let count
    try {
      count = [...ctx.loader.entries()].length
    } catch {
      count = lastCount
    }
    const now = Date.now()
    if (count !== lastCount) {
      lastCount = count
      stableSince = now
    } else if (now - stableSince >= STABLE_MS) {
      finish(false)
      return
    }
    if (now - startedAt >= TIMEOUT_MS) finish(true)
  }, POLL_MS)

  // 真有 ready 事件就更快；没有也不影响（上面那套自己会收敛）。注册失败不能拖垮整个插件 ——
  // 抛出去这一行就会变成 failed，那就永远拿不到清单了。
  try {
    ctx.on?.('ready', () => finish(false))
  } catch {
    // 这个版本没有 ready 事件：稳定判据兜着。
  }
}
