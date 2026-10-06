/**
 * 把"每一轮的用量"写成文件，让 App 直接读 —— cache / 轮次 / 步数 / token。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────────────────
 * 官方 ACP 只有一条用量出口：`usage_update`，而它带的是 `used`（当前上下文占用量）与 `size`
 * （窗口），值来自 `tokenMeter.measure(session).totalTokens` —— 那是**估算的上下文压力**，
 * 不是模型报回来的账（dsh-acp/lib/index.js 的 usageUpdate 就只填这两个键）。cache 命中、输入、
 * 输出、轮次、步数在 ACP 上一个字段都没有。
 *
 * 那份账在**跑着的会话进程里就有**：`session/event` 事件流里
 *   · `turn/start` / `turn/end` —— 轮次边界（载荷 `{ turn }`）；
 *   · `step/start` / `step/end` —— 一次模型调用为一步（载荷 `{ turn, step }`）；
 *   · `assistant/message` —— **一步的账**：`{ turn, step, message, stream, usage? }`，其中
 *     `usage` 就是适配器报回来的 TokenUsage
 *     `{ inputTokens, outputTokens, totalTokens?, cacheReadTokens?, cacheWriteTokens?, reasoningTokens? }`。
 *     官方注释写得很清楚："Carries the step's `usage` when the adapter reported token accounting ...
 *     there is no separate usage record"（dsh-session/lib/types/types.d.ts，assistant/message 那段）。
 *
 * ── 为什么按轮次累加 ────────────────────────────────────────────────────────────
 * 一轮里可以有多个 step（每次工具调用之后模型要再看一次）。每一步的 `inputTokens` 都是**那一步
 * 整段请求**的大小，加起来才是"这一轮一共花了多少输入 token"——各家用量界面都是这个口径。
 * `steps` 记的是这一轮走到第几步（取最大值），不是累加的次数。
 *
 * ── 写到哪 ──────────────────────────────────────────────────────────────────────
 *   · `$DSH_HOME/heta-usage.json`（真机 `/root/.dsh/heta-usage.json`）：与 heta-status 同一套证据
 *     文件，端到端冒烟读它；
 *   · 插件自己旁边的 `heta-usage.json`（`/opt/dsh/heta-usage.json`，0644、目录 0755）：**App 直接
 *     读的那一份**，不用 su —— 理由与 heta-status 的共享那份一模一样（那边注释写全了）。
 *
 * 形状（App 侧解析器与端到端断言都以它为准）：
 *   HETA-USAGE-JSON:{"at":…,"sessions":[{"id":"…","at":…,"turns":[
 *     {"turn":1,"steps":2,"inputTokens":…,"outputTokens":…,"cacheReadTokens":…,
 *      "cacheWriteTokens":…,"reasoningTokens":…,"totalTokens":…,"at":…}]}]}
 *
 * 只留最近 [MAX_SESSIONS] 个会话、每个会话最近 [MAX_TURNS] 轮：这是给界面看的滚动快照，不是账本
 *（真账本在会话日志里，App 不读它）。
 */
import { chmodSync, mkdirSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const name = 'heta-usage'

/** 只要事件，不取服务：`session/event` 在根上下文就能订到（dsh-acp 就是这么订的）。 */
export const inject = []

const MARKER = 'HETA-USAGE-JSON:'
const DSH_HOME = process.env.DSH_HOME ?? '/root/.dsh'
const HOME_FILE = join(DSH_HOME, 'heta-usage.json')

/** App 直接读的那一份：插件自己的目录（`/opt/dsh`）是 App 解包出来的，属主是 App。 */
const SHARED_FILE = join(dirname(fileURLToPath(import.meta.url)), 'heta-usage.json')

/** 滚动快照的上限：会话数与每个会话的轮数。 */
const MAX_SESSIONS = 4
const MAX_TURNS = 60

/** 每步 TokenUsage 里要抄过来的键（其余不抄：这份文件是给界面看的，不是存档）。 */
const USAGE_KEYS = [
  'inputTokens',
  'outputTokens',
  'cacheReadTokens',
  'cacheWriteTokens',
  'reasoningTokens',
  'totalTokens',
]

/** 这一步的账；适配器没报（`usage` 缺席）就是 undefined —— 不能拿 0 冒充"这一步没花钱"。 */
function usageOf(data) {
  const usage = data?.usage
  if (usage === undefined || usage === null) return undefined
  const out = {}
  for (const key of USAGE_KEYS) {
    const value = usage[key]
    if (typeof value === 'number' && Number.isFinite(value)) out[key] = value
  }
  return Object.keys(out).length === 0 ? undefined : out
}

/**
 * 写文件：先写临时文件再改名（App 可能正好在读），共享那份把权限明确摆正。
 * 失败只吞掉 —— 用量是"给界面看的东西"，写不出来不该影响会话。
 */
function writeFile(file, text, share = false) {
  try {
    const dir = dirname(file)
    mkdirSync(dir, { recursive: true })
    const temp = `${file}.tmp`
    writeFileSync(temp, text)
    renameSync(temp, file)
    if (share) {
      try {
        chmodSync(file, 0o644)
      } catch {}
      try {
        chmodSync(dir, 0o755)
      } catch {}
    }
    return true
  } catch {
    return false
  }
}

export function apply(ctx) {
  /** sessionId → { id, at, turns: Map<turn, record> } */
  const sessions = new Map()

  function sessionFor(session) {
    const id = typeof session?.id === 'string' && session.id !== '' ? session.id : 'unknown'
    let entry = sessions.get(id)
    if (entry === undefined) {
      entry = { id, at: 0, turns: new Map() }
      sessions.set(id, entry)
    }
    return entry
  }

  function recordFor(session, turn) {
    const entry = sessionFor(session)
    const key = typeof turn === 'number' && Number.isFinite(turn) ? turn : 0
    let record = entry.turns.get(key)
    if (record === undefined) {
      record = { turn: key, steps: 0, at: 0 }
      entry.turns.set(key, record)
    }
    return record
  }

  /** 这一份滚动快照：最近几个会话、每个会话最近若干轮。 */
  function snapshot() {
    const list = [...sessions.values()]
      .sort((a, b) => b.at - a.at)
      .slice(0, MAX_SESSIONS)
      .map((entry) => ({
        id: entry.id,
        at: entry.at,
        turns: [...entry.turns.values()]
          .sort((a, b) => a.turn - b.turn)
          .slice(-MAX_TURNS)
          .map((record) => ({ ...record })),
      }))
    return { at: Date.now(), sessions: list }
  }

  function publish() {
    const body = MARKER + JSON.stringify(snapshot()) + '\n'
    const home = writeFile(HOME_FILE, body)
    const shared = writeFile(SHARED_FILE, body, true)
    if (!home || !shared) {
      // 留一条痕迹：dsh 的 logger 在 ACP profile 里 info/warn 全被吞掉，只剩 stderr 能看见。
      try {
        console.error(`[heta-usage] 用量文件写失败：home=${home} shared=${shared}`)
      } catch {}
    }
  }

  ctx.on('session/event', (session, event) => {
    const type = event?.type
    if (type === 'step/end') {
      // 步数以 dsh 自己的步号为准：`assistant/message` 只在"这一步真的产出了消息"时才发，
      // 失败/被取消的尝试不会发它，但步号照样往前走。
      const data = event.data ?? {}
      const record = recordFor(session, data.turn)
      if (typeof data.step === 'number' && data.step > record.steps) record.steps = data.step
      record.at = Date.now()
      sessionFor(session).at = record.at
      publish()
      return
    }
    if (type === 'assistant/message') {
      const data = event.data ?? {}
      const record = recordFor(session, data.turn)
      if (typeof data.step === 'number' && data.step > record.steps) record.steps = data.step
      const usage = usageOf(data)
      if (usage !== undefined) {
        for (const key of Object.keys(usage)) {
          record[key] = (record[key] ?? 0) + usage[key]
        }
      }
      record.at = Date.now()
      sessionFor(session).at = record.at
      publish()
      return
    }
    if (type === 'turn/end') {
      const record = recordFor(session, event.data?.turn)
      record.at = Date.now()
      sessionFor(session).at = record.at
      publish()
    }
  })

  // 挂载就先落一份空的：App 读到它就知道"插件活着、只是还没有轮次"，与"文件根本没有"是两件事。
  publish()
}
