/**
 * 把"每一轮的用量与时间"和"当前上下文占用与构成"写成文件，让 App 直接读。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────────────────
 * 官方 ACP 只有一条用量出口：`usage_update`，带的是 `used`（当前上下文占用量）与 `size`（窗口），
 * 值来自 `tokenMeter.measure(session).totalTokens` —— **估算的上下文压力**，不是模型报回来的账；
 * cache 命中、输入、输出、轮次、步数、模型用时、工具用时、TTFT、TPS 一个字段都没有。
 *
 * 那份数据在**跑着的会话进程里**都有：
 *   · `session/event` 的 `assistant/message` 带 `{ turn, step, message, stream, usage? }`：
 *     `usage` 是适配器报回来的 TokenUsage（input/output/cacheRead/cacheWrite/reasoning/total），
 *     `stream` 是**带时间戳**的模型流（`AssistantStreamRecord`：打包段有 `time0`，裸块有 `time`），
 *     所以首 token 时刻与解码时长都算得出来；
 *   · 每个事件信封都带 `time`（Unix 毫秒）：`step/start` → `assistant/message` 就是这一步的模型用时，
 *     `tool/call` → `tool/result` 就是这次工具调用的用时；
 *   · `ctx.sessionProjections.snapshot(session, [...])` 给**整条会话日志**折出来的三个投影：
 *     `tokenUsage`（累计 token）、`contextPressure`（上下文占用 / 窗口）、
 *     `contextBreakdown`（系统提示词 / 工具定义 / 对话消息 的启发式估算）。
 *
 * 口径逐条照客户端（`@deepseek-ai/dsh-client-ui-chat` 的 `deriveStats` / 会话统计面板）：
 *   · 轮次 = 出现过的 turn 数；步数 = assistant 消息条数；
 *   · 模型用时 = Σ(assistant 落定时刻 − step/start 时刻)；
 *   · 工具调用用时 = Σ(tool/result 时刻 − tool/call 时刻)；
 *   · 首 token 平均（TTFT）= Σ(首个流片段时刻 − step/start 时刻) ÷ 有该值的步数；
 *   · 输出速度（TPS）= Σ输出 token ÷ (Σ解码时长 ÷ 1000)，其中解码时长 = 落定时刻 − 首个流片段时刻。
 *
 * ── 为什么要把上一份读回来 ──────────────────────────────────────────────────────
 * **一轮一进程**（ACP 那条路每轮都新起一个 dsh）：插件的内存每轮都是空的，而"会话统计"是整条会话
 * 的数。所以启动时先把上一次写的那份读回来，接着往下累加（轮次按 turn 号去重，重复的轮次先减掉
 * 旧贡献再加新的）。token 那一份另有权威来源（`tokenUsage` 投影覆盖整条日志），有它就用它。
 *
 * ── 写到哪 ──────────────────────────────────────────────────────────────────────
 *   · `$DSH_HOME/heta-usage.json`（真机 `/root/.dsh/heta-usage.json`）：端到端冒烟读它；
 *   · 插件自己旁边的 `heta-usage.json`（`/opt/dsh/heta-usage.json`，0644、目录 0755）：**App 直接
 *     读的那一份**，不用 su —— 理由与 heta-status 的共享那份一模一样。
 *
 * 形状（App 侧解析器与端到端断言都以它为准）：
 *   HETA-USAGE-JSON:{"at":…,"sessions":[{"id":"…","at":…,"turns":[{…}],
 *     "totals":{…},"context":{…}}]}
 */
import { chmodSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const name = 'heta-usage'

/** 只要事件与投影，不取服务：`session/event` 在根上下文就能订到（dsh-acp 就是这么订的）。 */
export const inject = []

const MARKER = 'HETA-USAGE-JSON:'
const DSH_HOME = process.env.DSH_HOME ?? '/root/.dsh'
const HOME_FILE = join(DSH_HOME, 'heta-usage.json')
const SHARED_FILE = join(dirname(fileURLToPath(import.meta.url)), 'heta-usage.json')

/** 滚动快照的上限：会话数与每个会话的轮数（会话统计的**累计值**另存在 totals 里，不受这个上限影响）。 */
const MAX_SESSIONS = 4
const MAX_TURNS = 60

/** 每步 TokenUsage 里要抄过来的键。 */
const USAGE_KEYS = [
  'inputTokens',
  'outputTokens',
  'cacheReadTokens',
  'cacheWriteTokens',
  'reasoningTokens',
  'totalTokens',
]

/** 时间口径（照客户端 deriveStats 的名字，App 那边一字段对一字段）。 */
const TIMING_KEYS = ['llmMs', 'toolMs', 'ttftMs', 'ttftSteps', 'decodeMs', 'decodeTokens']

/** 会话累计要把这些键加起来（轮次/步数单独算）。 */
const SUM_KEYS = [...USAGE_KEYS, ...TIMING_KEYS]

function numberOrUndefined(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/** 这一步的账；适配器没报（`usage` 缺席）就是 undefined —— 不能拿 0 冒充"这一步没花钱"。 */
function usageOf(data) {
  const usage = data?.usage
  if (usage === undefined || usage === null) return undefined
  const out = {}
  for (const key of USAGE_KEYS) {
    const value = numberOrUndefined(usage[key])
    if (value !== undefined) out[key] = value
  }
  return Object.keys(out).length === 0 ? undefined : out
}

/**
 * 模型流里**第一个片段**的时刻：打包段用 `time0`（那段第一个增量的时刻），裸块用 `time`。
 * 客户端那边是用 `isTokenDelta` 挑出真正的 token 增量；这里取"最早的片段"就够了 ——
 * 差别只可能是块开始/用量这类非 token 块，量级上不影响 TTFT。
 */
function firstChunkTime(stream) {
  if (!Array.isArray(stream)) return null
  let first = null
  for (const record of stream) {
    const time = numberOrUndefined(record?.time) ?? numberOrUndefined(record?.time0)
    if (time === undefined) continue
    if (first === null || time < first) first = time
  }
  return first
}

/** 写文件：先写临时文件再改名（App 可能正好在读），共享那份把权限明确摆正。失败只吞掉。 */
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

/** 把上一次写的那份读回来（一轮一进程，会话统计得接着往下累加）。读不动就当没有。 */
function readPrevious() {
  try {
    const text = readFileSync(HOME_FILE, 'utf8')
    const marker = text.indexOf(MARKER)
    if (marker < 0) return undefined
    return JSON.parse(text.slice(marker + MARKER.length).split('\n')[0])
  } catch {
    return undefined
  }
}

export function apply(ctx) {
  /** sessionId → { id, at, turns: Map<turn, record>, totals } */
  const sessions = new Map()

  for (const entry of readPrevious()?.sessions ?? []) {
    if (typeof entry?.id !== 'string' || entry.id === '') continue
    const turns = new Map()
    for (const record of entry.turns ?? []) {
      const turn = numberOrUndefined(record?.turn)
      if (turn === undefined) continue
      turns.set(turn, { ...record })
    }
    sessions.set(entry.id, {
      id: entry.id,
      at: numberOrUndefined(entry.at) ?? 0,
      turns,
      totals: { ...(entry.totals ?? {}) },
    })
  }

  /** 这一步的起点（step/start 的时刻）；用完就删，免得一步的时长算两遍。 */
  const stepStarts = new Map()
  /** 工具调用的起点：callId → 时刻。 */
  const toolStarts = new Map()

  function sessionFor(session) {
    const id = typeof session?.id === 'string' && session.id !== '' ? session.id : 'unknown'
    let entry = sessions.get(id)
    if (entry === undefined) {
      entry = { id, at: 0, turns: new Map(), totals: {} }
      sessions.set(id, entry)
    }
    return entry
  }

  /** 会话累计：新轮次整体加进去；已有轮次先减掉旧贡献再加新的（resume 重跑同一轮时不会重复计数）。 */
  function accumulate(entry, previous, record) {
    if (previous === undefined) {
      entry.totals.turnCount = (entry.totals.turnCount ?? 0) + 1
    }
    for (const key of SUM_KEYS) {
      const before = numberOrUndefined(previous?.[key]) ?? 0
      const after = numberOrUndefined(record[key]) ?? 0
      if (before === 0 && after === 0) continue
      entry.totals[key] = (entry.totals[key] ?? 0) + after - before
    }
    const stepsBefore = numberOrUndefined(previous?.steps) ?? 0
    const stepsAfter = numberOrUndefined(record.steps) ?? 0
    if (stepsBefore !== stepsAfter) {
      entry.totals.steps = (entry.totals.steps ?? 0) + stepsAfter - stepsBefore
    }
  }

  function recordFor(session, turn) {
    const entry = sessionFor(session)
    const key = numberOrUndefined(turn) ?? 0
    const previous = entry.turns.get(key)
    const record = previous === undefined ? { turn: key, steps: 0, at: 0 } : { ...previous }
    return { entry, key, previous, record }
  }

  function commit(entry, key, previous, record) {
    record.at = Date.now()
    entry.turns.set(key, record)
    entry.at = record.at
    accumulate(entry, previous, record)
  }

  /**
   * 上下文那两个投影（**现读**，不缓存）：`contextPressure` 是"下一次请求的上下文占用 / 窗口"，
   * `contextBreakdown` 是"系统提示词 / 工具定义 / 对话消息"的启发式构成；`tokenUsage` 是整条会话
   * 日志累计的 token —— 比我自己按轮次加的更权威（插件之前那些轮次它也算得出来）。
   */
  function projectionsFor(session) {
    const registry = ctx.get('sessionProjections')
    if (registry === undefined || typeof registry.snapshot !== 'function') return undefined
    let values
    try {
      values = registry.snapshot(session, ['contextPressure', 'contextBreakdown', 'tokenUsage'])?.values
    } catch {
      return undefined
    }
    if (values === undefined) return undefined
    const pressure = values.contextPressure ?? {}
    const breakdown = values.contextBreakdown ?? {}
    const usage = values.tokenUsage ?? {}
    return {
      context: {
        pressureTokens: numberOrUndefined(pressure.pressureTokens),
        projectedTokens: numberOrUndefined(pressure.projectedTokens),
        contextWindow: numberOrUndefined(pressure.contextWindow),
        systemTokens: numberOrUndefined(breakdown.systemTokens),
        toolsTokens: numberOrUndefined(breakdown.toolsTokens),
        messageTokens: numberOrUndefined(breakdown.messageTokens),
      },
      tokens: {
        inputTokens: numberOrUndefined(usage.uncachedInputTokens),
        outputTokens: numberOrUndefined(usage.outputTokens),
        cacheReadTokens: numberOrUndefined(usage.cacheReadTokens),
        cacheWriteTokens: numberOrUndefined(usage.cacheWriteTokens),
      },
    }
  }

  function snapshot() {
    const list = [...sessions.values()]
      .sort((a, b) => b.at - a.at)
      .slice(0, MAX_SESSIONS)
      .map((entry) => {
        const totals = { ...entry.totals }
        // token 那一份用整条会话日志的投影（有的话）——它连插件之前那些轮次也算得出来。
        const projected = entry.session === undefined ? undefined : projectionsFor(entry.session)
        if (projected !== undefined) {
          for (const [key, value] of Object.entries(projected.tokens)) {
            if (value !== undefined) totals[key] = value
          }
        }
        return {
          id: entry.id,
          at: entry.at,
          turns: [...entry.turns.values()].sort((a, b) => a.turn - b.turn).slice(-MAX_TURNS),
          totals,
          ...(projected?.context === undefined ? {} : { context: projected.context }),
        }
      })
    return { at: Date.now(), sessions: list }
  }

  function publish() {
    const body = MARKER + JSON.stringify(snapshot()) + '\n'
    const home = writeFile(HOME_FILE, body)
    const shared = writeFile(SHARED_FILE, body, true)
    if (!home || !shared) {
      try {
        console.error(`[heta-usage] 用量文件写失败：home=${home} shared=${shared}`)
      } catch {}
    }
  }

  ctx.on('session/event', (session, event) => {
    const type = event?.type
    const data = event?.data ?? {}
    const time = numberOrUndefined(event?.time)
    const entry = sessionFor(session)
    // 投影要 session 对象本身（snapshot(session, …)），顺手挂上去给 snapshot() 用。
    entry.session = session

    if (type === 'step/start') {
      stepStarts.set(entry.id, { turn: data.turn, time })
      return
    }
    if (type === 'tool/call') {
      if (typeof data.callId === 'string' && time !== undefined) toolStarts.set(data.callId, time)
      return
    }
    if (type === 'tool/result') {
      // `tool/result` 自己不带走哪个 callId：它在 message 上（`ToolResultMessage.toolCallId`）。
      const callId = typeof data.message?.toolCallId === 'string' ? data.message.toolCallId : undefined
      const started = callId === undefined ? undefined : toolStarts.get(callId)
      if (started !== undefined && time !== undefined) {
        toolStarts.delete(callId)
        const { entry: owner, key, previous, record } = recordFor(session, data.turn)
        record.toolMs = (numberOrUndefined(previous?.toolMs) ?? 0) + Math.max(0, time - started)
        commit(owner, key, previous, record)
        publish()
      }
      return
    }
    if (type === 'assistant/message') {
      const { entry: owner, key, previous, record } = recordFor(session, data.turn)
      if (numberOrUndefined(data.step) !== undefined && data.step > (record.steps ?? 0)) {
        record.steps = data.step
      }
      const usage = usageOf(data)
      if (usage !== undefined) {
        for (const key2 of Object.keys(usage)) {
          record[key2] = (numberOrUndefined(previous?.[key2]) ?? 0) + usage[key2]
        }
      }
      // 时间：这一步的模型用时 = step/start → 本条消息落定；TTFT 与解码时长从流里的时间戳来。
      const started = stepStarts.get(owner.id)
      stepStarts.delete(owner.id)
      if (started !== undefined && started.turn === data.turn && started.time !== undefined && time !== undefined) {
        record.llmMs = (numberOrUndefined(previous?.llmMs) ?? 0) + Math.max(0, time - started.time)
        const first = firstChunkTime(data.stream)
        if (first !== null && first >= started.time) {
          record.ttftMs = (numberOrUndefined(previous?.ttftMs) ?? 0) + Math.max(0, first - started.time)
          record.ttftSteps = (numberOrUndefined(previous?.ttftSteps) ?? 0) + 1
          record.decodeMs = (numberOrUndefined(previous?.decodeMs) ?? 0) + Math.max(0, time - first)
        }
      }
      if (usage?.outputTokens !== undefined) {
        record.decodeTokens = (numberOrUndefined(previous?.decodeTokens) ?? 0) + usage.outputTokens
      }
      commit(owner, key, previous, record)
      publish()
      return
    }
    if (type === 'step/end') {
      const { entry: owner, key, previous, record } = recordFor(session, data.turn)
      if (numberOrUndefined(data.step) !== undefined && data.step > (record.steps ?? 0)) {
        record.steps = data.step
      }
      commit(owner, key, previous, record)
      publish()
      return
    }
    if (type === 'turn/end') {
      const { entry: owner, key, previous, record } = recordFor(session, data.turn)
      commit(owner, key, previous, record)
      publish()
    }
  })

  // 挂载就先落一份：App 读到它就知道"插件活着、只是还没有轮次"。
  publish()
}
