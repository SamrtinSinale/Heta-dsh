/**
 * 把每个新建的**顶层** Agent 加入默认预设。
 *
 * 为什么 Heta 得自己写这一跳：官方预设架构里「会话 → 预设」只发生一次，而在 Web 那边这次
 * 绑定是**浏览器半边**发起的 ——
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
 *   2. `composedPreset(ctx)` 已经有值 → 跳过。**子 agent 命中的就是这条**：官方在子 agent 的
 *      setup 阶段用 composeFrom 把它 join 到父的那一份修订上
 *      （dsh-subagent/lib/index.js:511 applyChildComposition），而 composeFrom 遇到已绑定的
 *      key 会直接抛 "Child already joined a preset"
 *      （dsh-agent-preset-registry/lib/index.js:715）。
 *   3. 只处理顶层 Agent（`agents.roots()`，判据是 owner === undefined）。子 agent 由父的上下文
 *      创建，roots() 里本来就不会有它 —— 这条是给"守卫 2 万一没赶上顺序"兜底的。
 *
 * 挂载点用 `agent/created`：dsh-agent 在 announce() 里发它（dsh-agent/lib/index.js:579），
 * 载荷是 `{ agent, source, signal? }`；同一个事件 dsh-goal / dsh-goal-round-driver /
 * dsh-tool-subagent 也在根上下文里监听，所以根行监听它是既有写法。
 *
 * 万一 mount 抛错，这一轮该 Agent 会**没有任何工具**（因为 agent 平面已经按官方做法从根上
 * 让位了）。所以这里失败只 warn、不吞异常链，日志里能直接看到原因。
 */
export const name = 'heta-preset-join'
export const inject = ['agents']

export function apply(ctx) {
  ctx.on('agent/created', async ({ agent }) => {
    const presets = ctx.get('agentPresets')
    if (presets === undefined) {
      ctx.logger.info(`[heta-preset-join] ${agent.id}: 这份资产没有预设平面，保持不绑定`)
      return
    }
    if (presets.composedPreset(agent.ctx) !== undefined) return
    const roots = ctx.agents.roots()
    if (!roots.some((live) => live.id === agent.id)) return
    try {
      const bound = await presets.mount(agent.ctx)
      ctx.logger.info(`[heta-preset-join] ${agent.id} 加入预设 ${bound.id}`)
    } catch (error) {
      ctx.logger.warn(
        `[heta-preset-join] ${agent.id} 加入预设失败，这一轮它会没有工具：${String(
          (error && error.message) || error
        )}`
      )
    }
  })
}
