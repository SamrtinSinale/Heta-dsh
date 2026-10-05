/**
 * `heta-preset-join.mjs` 的守卫自测（纯 node，不需要 dsh）。
 *
 * 为什么这份自测重要：这个插件只有 30 行，但它决定"新会话到底有没有工具"——
 * agent 平面按官方做法已经从根上让位，所以它一旦把**子 agent** 也 mount 一遍，
 * 官方的 `composeFrom` 就会抛 "Child already joined a preset"
 *（dsh-agent-preset-registry/lib/index.js:715），而它一旦在该 mount 时不 mount，
 * 那一轮会话一行工具都没有。真运行时里的正例由 `scripts/test-dsh-e2e.sh` 覆盖
 * （agent 平面搬走 + 真会话 + 真工具调用），这里覆盖的是**决策表**。
 *
 * 用法：node scripts/test-heta-preset-join.mjs
 */
import { fileURLToPath, pathToFileURL } from 'node:url'
import { mkdirSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

// 状态文件写到临时家目录：真机上它是 `$DSH_HOME/heta-modes.json`（/root/.dsh 里那份）。
const statusHome = join(tmpdir(), `heta-join-guard-${process.pid}`)
mkdirSync(statusHome, { recursive: true })
process.env.DSH_HOME = statusHome

const here = dirname(fileURLToPath(import.meta.url))
// 用 pathToFileURL 拼：手写 `file://${here}/` 在 Windows 的 UNC 路径（\\wsl.localhost\…）下会拼出
// 一个非法的 file URL（Node 报 "File URL path must be absolute"）。这个自测要能在两处跑。
const plugin = await import(
  pathToFileURL(join(here, '..', 'app', 'src', 'main', 'assets', 'heta-preset-join.mjs')).href
)

let checks = 0
const failures = []

function ok(condition, label) {
  checks += 1
  if (!condition) failures.push(label)
}

/** 造一个够用的 ctx：只实现插件真正碰到的那几样。 */
function harness({ roster, composed, roots }) {
  const mounted = []
  const events = []
  const logs = { info: [], warn: [] }
  const ctx = {
    logger: {
      info: (m) => logs.info.push(m),
      warn: (m) => logs.warn.push(m),
    },
    on: (name, handler) => events.push({ name, handler }),
    get: (name) => (name === 'agentPresets' ? roster : undefined),
    agents: { roots: () => roots },
  }
  return {
    ctx,
    mounted,
    logs,
    async fire(agent) {
      for (const event of events) {
        if (event.name === 'agent/created') await event.handler({ agent, source: 'startup' })
      }
    },
  }
}

function makeRoster(bound, mounted, id) {
  return {
    composedPreset: () => bound,
    mount: async () => {
      mounted.push(id)
      return { id: 'standard' }
    },
  }
}

// 1) 导出形状
ok(plugin.name === 'heta-preset-join', 'name 不是 heta-preset-join')
ok(
  Array.isArray(plugin.inject) && plugin.inject.includes('agents'),
  'inject 里没有 agents（拿不到 agents.roots()）'
)
ok(typeof plugin.apply === 'function', 'apply 不是函数')

// 2) 没有 roster（这份资产没装注册表）→ 不 mount，且要留一句日志
{
  const h = harness({ roster: undefined, composed: undefined, roots: [{ id: 'a1' }] })
  plugin.apply(h.ctx)
  ok(h.ctx.on !== undefined, 'apply 没注册监听')
  await h.fire({ id: 'a1', ctx: {} })
  ok(h.mounted.length === 0, '没有 roster 时居然 mount 了')
  ok(h.logs.info.length === 1, '没有 roster 时没留日志')
}

// 3) 顶层 Agent + 没绑过 → 恰好 mount 一次
{
  const h = harness({ roster: undefined, composed: undefined, roots: [{ id: 'a1' }] })
  const mounted = []
  h.ctx.get = (name) => (name === 'agentPresets' ? makeRoster(undefined, mounted, 'a1') : undefined)
  plugin.apply(h.ctx)
  await h.fire({ id: 'a1', ctx: {} })
  ok(mounted.length === 1, `顶层 Agent 应该 mount 一次，实际 ${mounted.length}`)
}

// 4) 已经绑过（子 agent 的常态：composeFrom 在 setup 阶段就 join 过）→ 不碰
{
  const mounted = []
  const h = harness({ roster: undefined, composed: 'standard', roots: [{ id: 'a1' }] })
  h.ctx.get = (name) =>
    name === 'agentPresets' ? makeRoster('standard', mounted, 'a1') : undefined
  plugin.apply(h.ctx)
  await h.fire({ id: 'a1', ctx: {} })
  ok(mounted.length === 0, '已绑过的 Agent 不该再 mount（会撞 Child already joined a preset）')
}

// 5) 不在 roots() 里（子 agent）→ 不碰
{
  const mounted = []
  const h = harness({ roster: undefined, composed: undefined, roots: [] })
  h.ctx.get = (name) => (name === 'agentPresets' ? makeRoster(undefined, mounted, 'child') : undefined)
  plugin.apply(h.ctx)
  await h.fire({ id: 'child', ctx: {} })
  ok(mounted.length === 0, '子 agent（不在 roots()）不该被 mount')
}

// 6) mount 抛错 → 只 warn，不把异常抛回事件总线
{
  const h = harness({ roster: undefined, composed: undefined, roots: [{ id: 'a1' }] })
  h.ctx.get = (name) =>
    name === 'agentPresets'
      ? {
          composedPreset: () => undefined,
          mount: async () => {
            throw new Error('boom')
          },
        }
      : undefined
  plugin.apply(h.ctx)
  let threw = false
  try {
    await h.fire({ id: 'a1', ctx: {} })
  } catch {
    threw = true
  }
  ok(!threw, 'mount 抛错时不该把异常抛回事件总线（会把 dsh 的创建流程带崩）')
  ok(h.logs.warn.length === 1, 'mount 抛错时没留 warn 日志')
}

/**
 * 模式那一侧的 harness。
 *
 * 关键形状：`commands` 是**根**服务（`ctx.get('commands')`），而 `planMode` / `goals` 里
 * 至少 `planMode` 只存在于**预设的隔离域**里 —— 根上下文取不到，必须按 agent 去
 * `roster.serviceFor(agent, name)` 里找（真机实测：`ctx.get('planMode')` 是 undefined，
 * 表现就是"计划模式开了但模型完全不知道"）。这里就按这个形状造。
 */
function modeHarness({ planActive = false, goal } = {}) {
  const executed = []
  const services = {
    planMode: { get: () => ({ active: planActive }) },
    goals: { get: () => goal },
  }
  const commands = {
    execute: async (_agent, line) => {
      executed.push(line)
      return { result: { kind: 'success', text: 'ok' } }
    },
  }
  const roster = {
    composedPreset: () => 'standard',
    serviceFor: (_agent, name) => services[name],
  }
  const events = []
  const ctx = {
    logger: { info: () => {}, warn: () => {} },
    on: (name, handler) => events.push({ name, handler }),
    get: (name) => (name === 'agentPresets' ? roster : name === 'commands' ? commands : undefined),
    agents: { roots: () => [{ id: 'a1' }] },
  }
  return {
    ctx,
    executed,
    async fire(agent = { id: 'a1', ctx: {} }) {
      for (const event of events) {
        if (event.name === 'agent/created') await event.handler({ agent, source: 'startup' })
      }
    },
    status() {
      try {
        return JSON.parse(readFileSync(join(statusHome, 'heta-modes.json'), 'utf8'))
      } catch {
        return undefined
      }
    },
  }
}

// 7) 计划模式：隔离域里那个实例要被找到；状态不一致 → 恰好一条 /plan
{
  const h = modeHarness({ planActive: false })
  plugin.apply(h.ctx, { plan: true })
  await h.fire()
  ok(h.executed.length === 1 && h.executed[0] === '/plan',
    `应该恰好执行一次 /plan，实际 ${JSON.stringify(h.executed)}`)
  const status = h.status()
  ok(status?.plan?.available === true, '状态文件说计划模式不可用（隔离域里的实例没找到？）')
  ok(status?.lines?.[0]?.kind === 'success', '状态文件里 /plan 不是 success')
}

// 8) 状态已经一致 → 一条命令都不发
{
  const h = modeHarness({ planActive: true })
  plugin.apply(h.ctx, { plan: true })
  await h.fire()
  ok(h.executed.length === 0, `状态一致时不该发命令，实际 ${JSON.stringify(h.executed)}`)
}

// 9) 关掉计划模式 → /plan off
{
  const h = modeHarness({ planActive: true })
  plugin.apply(h.ctx, { plan: false })
  await h.fire()
  ok(h.executed.length === 1 && h.executed[0] === '/plan off',
    `应该发 /plan off，实际 ${JSON.stringify(h.executed)}`)
}

// 10) 目标的四种收敛
{
  const create = modeHarness({ goal: undefined })
  plugin.apply(create.ctx, { goal: '  写一个 README  ' })
  await create.fire()
  ok(create.executed.length === 1 && create.executed[0] === '/goal 写一个 README',
    `没有目标时应该建（并且去掉首尾空白），实际 ${JSON.stringify(create.executed)}`)

  const same = modeHarness({ goal: { objective: '写一个 README', phase: 'active' } })
  plugin.apply(same.ctx, { goal: '写一个 README' })
  await same.fire()
  ok(same.executed.length === 0, `目标没变时不该发命令，实际 ${JSON.stringify(same.executed)}`)

  const edit = modeHarness({ goal: { objective: '旧的', phase: 'active' } })
  plugin.apply(edit.ctx, { goal: '新的' })
  await edit.fire()
  ok(edit.executed.length === 1 && edit.executed[0] === '/goal edit 新的',
    `换了目标应该 edit，实际 ${JSON.stringify(edit.executed)}`)

  const clear = modeHarness({ goal: { objective: '旧的', phase: 'active' } })
  plugin.apply(clear.ctx, { goal: '' })
  await clear.fire()
  ok(clear.executed.length === 1 && clear.executed[0] === '/goal clear',
    `空串应该清除目标，实际 ${JSON.stringify(clear.executed)}`)

  const none = modeHarness({ goal: undefined })
  plugin.apply(none.ctx, { goal: '' })
  await none.fire()
  ok(none.executed.length === 0,
    `本来就没有目标时不该发 /goal clear，实际 ${JSON.stringify(none.executed)}`)
}

// 11) 没有 config（普通会话）→ 一条命令都不发，也不写状态文件
{
  const h = modeHarness({ planActive: false })
  plugin.apply(h.ctx)
  await h.fire()
  ok(h.executed.length === 0, `没有 config 时不该动会话，实际 ${JSON.stringify(h.executed)}`)
}

if (failures.length > 0) {
  console.log(`✗ 失败 ${failures.length} 条：`)
  for (const f of failures) console.log(`   - ${f}`)
  process.exit(1)
}
console.log(`✓ heta-preset-join 守卫自测：${checks} 条断言全过`)
