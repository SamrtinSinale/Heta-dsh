/**
 * 每次新会话要做的两件事，顺序不能反：
 *
 *   ① 把每个新建的**顶层** Agent 加入默认预设；
 *   ② 按 Heta 侧记下的意图，收敛这个会话的协作模式（计划模式 / 目标模式），并把结果写一份状态文件。
 *
 * ── ① 为什么 Heta 得自己写这一跳 ────────────────────────────────────────────────
 * 官方预设架构里「会话 → 预设」只发生一次，而在 Web 那边这次绑定是**浏览器半边**发起的 ——
 *   dsh-client-ui-agent-preset/lib/client.js:1375
 *     await this.ctx.remote.agentPresets.select(session.id, staged)
 * 而且 `staged` 是人在新会话界面上选出来的（`staged === undefined` 时它直接 return）。
 * ACP 这条路上没有任何调用者：dsh-base / dsh-acp-app / dsh-acp 对 `agentPresets` 零命中，
 * ACP 只暴露 initialize / session/new / session/resume / session/update，没有 Remote 出口。
 * 注册表自己的 `config.default` 只是 `defaultId`（dsh-agent-preset-registry/lib/index.js:640），
 * 它不会替谁去 join。
 *
 * 所以这里做的正是那次 select 的等价物，用的也是官方 API：
 *   agentPresets.mount(ctx)   —— id 省略时取 config.default（同文件 739-741）。
 *
 * 三条守卫，缺一条都会出事：
 *   1. 没有 `agentPresets` 服务（例如这一份资产里没装注册表）→ 不绑定，保持官方
 *      "没有注册表就只服务 Loader 条目"的形态（dsh-host-plugin-inventory/README.md:105）。
 *   2. `composedPreset(ctx)` 已经有值 → 不再挂。**子 agent 命中的就是这条**：官方在子 agent 的
 *      setup 阶段用 composeFrom 把它 join 到父的那一份修订上
 *      （dsh-subagent/lib/index.js:511 applyChildComposition），而 composeFrom 遇到已绑定的
 *      key 会直接抛 "Child already joined a preset"
 *      （dsh-agent-preset-registry/lib/index.js:715）。
 *   3. 只处理顶层 Agent（`agents.roots()`，判据是 owner === undefined）。
 *
 * 挂载点用 `agent/created`：dsh-agent 在 announce() 里发它（dsh-agent/lib/index.js:579），
 * 载荷是 `{ agent, source, signal? }`；同一个事件 dsh-goal / dsh-goal-round-driver /
 * dsh-tool-subagent 也在根上下文里监听，所以根行监听它是既有写法。
 *
 * 万一 mount 抛错，这一轮该 Agent 会**没有任何工具**（因为 agent 平面已经按官方做法从根上
 * 让位了）。所以这里失败只 warn、不吞异常链，日志里能直接看到原因。
 *
 * ── ② 为什么模式也在这里收敛 ────────────────────────────────────────────────────
 * 计划模式与目标模式在 dsh 里都是**斜杠命令**的状态（`/plan`、`/goal`），而 ACP 这条路上没有
 * 命令通道：命令只能由 UI 半边调 `commands.execute()` 发起（官方 Web 客户端就是这么做的），
 * ACP 的 prompt 会被原样当成用户消息（dsh-acp/lib/index.js 的 admitAcpPrompt），绝不会被解析
 * 成命令。Heta 没有那条通道，于是把"用户想要什么模式"写进覆盖层那一行的 config，由这一跳在
 * **预设挂好之后**补上那次 execute —— 顺序是必须的：`/plan` 与 `/goal` 是预设里的行注册的，
 * 挂载之前根本不存在。
 *
 * 服务查询要留神"隔离域"：`plan-mode` 在预设里被包在一个 `isolate: { planMode: true }` 的组里，
 * 于是它的实例只存在于**那个预设子树**内部 —— 根上下文 `ctx.get('planMode')` 拿到的是 undefined
 * （实测过：真机上表现就是"计划模式开了但模型完全不知道"）。要按 agent 去它挂载的那一份里找，
 * 用的是注册表上的 `agentPresets.serviceFor(agent, name)`
 * （`serviceForAgent(ctx, agent, name)` 的那个包装，dsh-agent-preset-registry/lib/index.js:731），
 * 官方那句注释写的就是这件事："Browser RPCs hold the Agent but resolve outside that realm, so
 * they locate its revision through the Agent's scope parent."
 *
 * 收敛而不是盲发：会话状态由日志折叠回来（每条消息一次新进程 + session/resume），Heta 只知道
 * "想要什么"，所以这里先读**日志里的现状**（`planMode.get(agent).active` / `goals.get(agent)`），
 * 一致就一条命令都不发 —— 否则每轮都会往会话日志里塞一对 command/run + command/done，而且
 * "目标已完成"会被误判成"要重建目标"。
 *
 * ── ③ 状态文件 ──────────────────────────────────────────────────────────────────
 * 结果写到 `$DSH_HOME/heta-modes.json`（真机上是 `/root/.dsh/heta-modes.json`，App 与端到端
 * 都读得到）。为什么要它：dsh 的 logger 在 ACP profile 里 info/warn 全被吞掉（实测 stderr 一个
 * 字都没有），而"命令到底执行成功没有"必须有据可查 —— 端到端第 ⑪ 步就是读这个文件断言的。
 *
 * config（覆盖层那一行给的两个键，缺一个就只管另一个）：
 *   plan: true | false                 计划模式的意图（true=进、false=出）
 *   goal: "<objective>" | ""           目标：非空=建/改，空串=清除
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

export const name = 'heta-preset-join'
export const inject = ['agents']

/** 读覆盖层那一行的 config：形状不对的键直接不管（宁可不动，也不要拿半个配置去改会话）。 */
function readModes(config) {
  const plan = typeof config?.plan === 'boolean' ? config.plan : undefined
  const goal = typeof config?.goal === 'string' ? config.goal : undefined
  return { plan, goal }
}

/**
 * 找一个服务：先到该 agent 挂载的预设子树里找（隔离域里的实例只在那里），找不到再退回根上下文。
 *
 * 必须两步：预设的组会把 `planMode` 之类的实例关在隔离域里，而 `commands` / `goals` 这类
 * 根平面的服务在子树里根本不存在 —— 两者都要能拿到。
 */
function serviceFor(presets, ctx, agent, name) {
  const inside = typeof presets?.serviceFor === 'function'
    ? presets.serviceFor(agent, name)
    : undefined
  return inside !== undefined ? inside : ctx.get(name)
}

export function apply(ctx, config) {
  const modes = readModes(config)
  ctx.on('agent/created', async ({ agent }) => {
    const presets = ctx.get('agentPresets')
    if (presets === undefined) {
      ctx.logger.info(`[heta-preset-join] ${agent.id}: 这份资产没有预设平面，保持不绑定`)
      return
    }
    const roots = ctx.agents.roots()
    if (!roots.some((live) => live.id === agent.id)) return
    // 守卫 2：已经在别处 join 过就不再挂（子 agent 走的就是这条）。模式是每个顶层会话自己的
    // 事，所以下面照旧收敛 —— 只是没有预设就没有那些命令，收敛会自己说清楚。
    if (presets.composedPreset(agent.ctx) === undefined) {
      try {
        const bound = await presets.mount(agent.ctx)
        ctx.logger.info(`[heta-preset-join] ${agent.id} 加入预设 ${bound.id}`)
      } catch (error) {
        ctx.logger.warn(
          `[heta-preset-join] ${agent.id} 加入预设失败，这一轮它会没有工具：${message(error)}`
        )
        return
      }
    }
    await applyModes(ctx, presets, agent, modes, presets.composedPreset(agent.ctx))
  })
}

/** 把 Heta 记下的模式意图收敛到会话上：只发**状态确实不一致**的那几条命令。 */
async function applyModes(ctx, presets, agent, modes, presetId) {
  const report = { agent: agent.id, preset: presetId, plan: undefined, goal: undefined }
  if (modes.plan === undefined && modes.goal === undefined) return
  const commands = serviceFor(presets, ctx, agent, 'commands')
  if (commands === undefined) {
    ctx.logger.warn('[heta-preset-join] 没有 commands 服务，这一轮的计划/目标模式不生效')
    return
  }
  const lines = []
  if (modes.plan !== undefined) {
    const planMode = serviceFor(presets, ctx, agent, 'planMode')
    if (planMode === undefined) {
      ctx.logger.warn(`[heta-preset-join] ${agent.id}: 这份预设没有计划模式，忽略 plan=${modes.plan}`)
      report.plan = { wanted: modes.plan, available: false }
    } else {
      let active
      try {
        active = planMode.get(agent).active
      } catch (error) {
        ctx.logger.warn(`[heta-preset-join] ${agent.id}: 读不到计划模式状态：${message(error)}`)
      }
      report.plan = { wanted: modes.plan, activeBefore: active, available: true }
      if (active !== undefined && active !== modes.plan) lines.push(modes.plan ? '/plan' : '/plan off')
    }
  }
  if (modes.goal !== undefined) {
    const goals = serviceFor(presets, ctx, agent, 'goals')
    if (goals === undefined) {
      ctx.logger.warn(`[heta-preset-join] ${agent.id}: 这份预设没有目标模式，忽略 goal`)
      report.goal = { wanted: modes.goal, available: false }
    } else {
      const objective = modes.goal.trim()
      let current
      try {
        current = goals.get(agent)
      } catch (error) {
        ctx.logger.warn(`[heta-preset-join] ${agent.id}: 读不到目标状态：${message(error)}`)
      }
      report.goal = {
        wanted: objective,
        available: true,
        phaseBefore: current === undefined ? null : current.phase,
        objectiveBefore: current === undefined ? null : current.objective,
      }
      if (current === undefined && objective === '') {
        // 既没有目标、也没要求清除：什么都不做（`/goal clear` 只会白写一遍日志）。
      } else if (current === undefined) {
        lines.push(`/goal ${objective}`)
      } else if (objective === '') {
        lines.push('/goal clear')
      } else if (current.objective !== objective) {
        lines.push(`/goal edit ${objective}`)
      }
    }
  }
  report.lines = []
  for (const line of lines) {
    try {
      // 命令的 signal 由发起方持有：这里没有可取消的 UI 请求，给一个不会 abort 的信号。
      const settled = await commands.execute(agent, line, [], new AbortController().signal)
      if (settled === undefined) {
        ctx.logger.warn(`[heta-preset-join] ${agent.id}: ${line} 没有命中命令（这份预设没注册它）`)
        report.lines.push({ line, hit: false })
      } else {
        const text = settled.result.text === undefined ? '' : `：${settled.result.text}`
        ctx.logger.info(`[heta-preset-join] ${agent.id}: ${line} → ${settled.result.kind}${text}`)
        report.lines.push({
          line,
          hit: true,
          kind: settled.result.kind,
          text: settled.result.text ?? null,
        })
      }
    } catch (error) {
      ctx.logger.warn(`[heta-preset-join] ${agent.id}: ${line} 执行失败：${message(error)}`)
      report.lines.push({ line, hit: true, kind: 'threw', text: message(error) })
    }
  }
  writeStatus(report)
}

/** 状态文件：写在 dsh 自己的家目录里（真机 `/root/.dsh`），App 与端到端冒烟都读得到。 */
function writeStatus(report) {
  try {
    const home = process.env.DSH_HOME ?? join(process.env.HOME ?? '/root', '.dsh')
    const file = join(home, 'heta-modes.json')
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
  } catch (error) {
    // 写状态文件失败不该影响会话本身：它只是证据 / 给界面看的那一份。
    console.error(`[heta-preset-join] 状态文件写失败：${message(error)}`)
  }
}

function message(error) {
  return String((error && error.message) || error)
}
