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
import { dirname, join } from 'node:path'

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

if (failures.length > 0) {
  console.log(`✗ 失败 ${failures.length} 条：`)
  for (const f of failures) console.log(`   - ${f}`)
  process.exit(1)
}
console.log(`✓ heta-preset-join 守卫自测：${checks} 条断言全过`)
