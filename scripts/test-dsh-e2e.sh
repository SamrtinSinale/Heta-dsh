#!/bin/bash
#
# dsh 运行时 + ACP + 工具调用的**端到端冒烟测试**。
#
# 它回答的是这个问题："Heta 里的 dsh 还能不能像官方一样真的调工具？"
# 之前所有验证都只到 `initialize` 为止（会话建立前的握手），工具链（子进程、pty、
# ripgrep、权限策略、回合循环）一条都没跑过。这里用一个假模型（按 Anthropic Messages
# 协议回一个 bash tool_use）把整条链路跑一遍，断言：
#
#   1. 收到 bash 工具的 tool_call
#   2. 该调用 completed，且结果里带着标记串（命令真的被执行了）
#   3. 回合以 end_turn 收尾
#   4. 标记文件真的出现在 chroot 的 /workspace 里（独立证据，不只看协议）
#
#   5. 跑完凭据文件已经不在（真脚本的 `rm -f`；`. file` 那步没生效就会打到真网关）
#
# 用的都是真东西：APK 里那份运行时资产、Kotlin 单测导出的**真 overlay 与真启动脚本**
# （含凭据文件机制、/dev 与 /proc 挂载、会话回收、`exec chroot`）、真 qemu + chroot。
# 端到端不自己拼启动命令 —— 拼就会漏掉真实路径，这一条上次已经吃过亏。
#
# 用法：sudo bash scripts/test-dsh-e2e.sh
#   QEMU_AARCH64        指定 qemu-aarch64-static（默认取 PATH 或 /usr/bin）
#   DSH_E2E_OVERLAY     指定 overlay 文件（默认取单测导出的 app/build/…）
#   DSH_E2E_STARTUP     指定启动脚本（默认取单测导出的 app/build/…）
#   DSH_E2E_ROOT        复用已经解包好的运行时目录（默认自己解包到临时目录）
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARKER="heta-smoke-ok"
PORT="${DSH_E2E_PORT:-8765}"

if [ "$(id -u)" != 0 ]; then
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    exec sudo -E bash "$0" "$@"
  fi
  echo "SKIP: 端到端冒烟需要 root（要 chroot + 挂载）"
  exit 77
fi

QEMU="${QEMU_AARCH64:-$(command -v qemu-aarch64-static || true)}"
[ -n "$QEMU" ] && [ -x "$QEMU" ] || { echo "FAIL: 找不到 qemu-aarch64-static（apt-get install qemu-user-static）"; exit 1; }

ASSET="$REPO/app/src/main/assets/dsh-runtime.tar.xz"
[ -f "$ASSET" ] || { echo "FAIL: 缺少运行时资产 $ASSET"; exit 1; }

TMP="$(mktemp -d /tmp/dsh-e2e.XXXXXX)"
MOCK_PID=""
cleanup() {
  [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null
  # 和运行时脚本同一套匹配：mountinfo 里记的是解析后的路径，且必须是路径边界
  awk '{print $5}' /proc/self/mountinfo 2>/dev/null | while read -r m; do
    cm=$(readlink -f "$m" 2>/dev/null || echo "$m")
    case "$cm" in "$TMP"|"$TMP"/*) umount -l "$m" 2>/dev/null ;; esac
  done
  [ -z "${DSH_E2E_KEEP:-}" ] && rm -rf "$TMP"
}
trap cleanup EXIT

if [ -n "${DSH_E2E_ROOT:-}" ]; then
  ROOT="$DSH_E2E_ROOT"
else
  ROOT="$TMP/root"
  echo "① 解包运行时资产 → $ROOT"
  mkdir -p "$ROOT"
  tar -xJf "$ASSET" -C "$ROOT" || { echo "FAIL: 解包失败"; exit 1; }
fi
cp -f "$QEMU" "$ROOT/qemu-static"; chmod +x "$ROOT/qemu-static"

OVERLAY="${DSH_E2E_OVERLAY:-$(find "$REPO" -name dsh-e2e-overlay.patch.yml -print -quit 2>/dev/null)}"
[ -n "$OVERLAY" ] && [ -f "$OVERLAY" ] || {
  echo "FAIL: 没有 overlay（先跑单测导出：./gradlew :app:testDebugUnitTest）"
  exit 1
}
echo "② 用单测导出的真 overlay：$OVERLAY"
cp -f "$OVERLAY" "$ROOT/opt/dsh/heta-run-overlay.patch.yml"

# 预设那一跳的插件：覆盖层里是相对名（`./heta-preset-join.mjs`），所以必须和覆盖层同目录。
# 少了它，注册表在、agent 平面却被关了 —— 每个会话一行工具都没有（这正是要冒烟抓的那个形态）。
JOIN="$REPO/app/src/main/assets/heta-preset-join.mjs"
[ -f "$JOIN" ] || { echo "FAIL: 缺少 join 插件资产 $JOIN"; exit 1; }
cp -f "$JOIN" "$ROOT/opt/dsh/heta-preset-join.mjs"

rm -f "$ROOT/workspace/smoke.txt"
MOCK_DUMP="$TMP/mock-dump.jsonl"
echo "③ 起假模型（Anthropic Messages，127.0.0.1:$PORT）"
# 请求原样落盘：第二回合要靠它断言"system 里是不是新人格""历史是不是原样带着"。
DSH_E2E_MOCK_DUMP="$MOCK_DUMP" python3 "$REPO/scripts/dsh-e2e-mock-llm.py" "$PORT" \
  > "$TMP/mock.log" 2>&1 &
MOCK_PID=$!
for _ in $(seq 1 40); do
  grep -q "listening" "$TMP/mock.log" && break
  sleep 0.25
done
grep -q "listening" "$TMP/mock.log" || { echo "FAIL: 假模型没起来"; cat "$TMP/mock.log"; exit 1; }

STARTUP_SRC="${DSH_E2E_STARTUP:-$(find "$REPO" -name dsh-startup-script.sh -print -quit 2>/dev/null)}"
[ -n "$STARTUP_SRC" ] && [ -f "$STARTUP_SRC" ] || {
  echo "FAIL: 没有单测导出的启动脚本（先跑单测导出，或用 DSH_E2E_STARTUP 指定）"
  exit 1
}
DUMPED_ROOT="$(sed -n '1s/^# dsh-e2e-rootfs=//p' "$STARTUP_SRC")"
[ -n "$DUMPED_ROOT" ] || { echo "FAIL: 导出的启动脚本第一行没有 rootfs 路径"; exit 1; }
echo "④ 用单测导出的真启动脚本（导出的 rootfs：$DUMPED_ROOT → 本次：$ROOT）"
# 注意别"顺手"改脚本里的 PATH：它是 Android 优先（/system/bin:/system/xbin:/product/bin），
# 但**以 /usr/bin:/bin 结尾** —— 在 Ubuntu/CI 上靠的就是后段解析出 rm/find/mount/chroot。
# 哪天这里冒出 "rm: command not found"，先看 PATH 后段还在不在，别去动 E2E。
sed "s|$DUMPED_ROOT|$ROOT|g" "$STARTUP_SRC" > "$TMP/startup.sh"
grep -q "$ROOT" "$TMP/startup.sh" || { echo "FAIL: 启动脚本里的 rootfs 路径没替换掉"; exit 1; }

# 凭据文件：真脚本会 `set -a; . <它>; set +a; rm -f <它>`。baseUrl 指向假模型 ——
# 如果 source 那一步没生效，dsh 会打到真网关，冒烟直接红。
CRED="$ROOT/opt/dsh/heta-run-env.sh"
mkdir -p "$(dirname "$CRED")"
printf 'export DEEPSEEK_API_KEY=sk-smoke\nexport DEEPSEEK_BASE_URL=http://127.0.0.1:%s\n' "$PORT" > "$CRED"
chmod 600 "$CRED"

# 状态出口插件：真 App 的启动路径会把它写进 runtime root（DshRuntimeConfig.writeJoinPlugin），
# 而单测导出的那份 rootfs 只带 overlay 与启动脚本 —— 端到端要验「会话进程写清单」这条路，
# 就得自己把它放进去（同上面那个凭据文件的做法）。第 ⑫ 步断言的就是它写出来的文件。
STATUS_PLUGIN="$REPO/app/src/main/assets/heta-status.mjs"
[ -f "$STATUS_PLUGIN" ] || { echo "FAIL: 缺少状态插件资产 $STATUS_PLUGIN"; exit 1; }
mkdir -p "$ROOT/opt/dsh"
cp -f "$STATUS_PLUGIN" "$ROOT/opt/dsh/heta-status.mjs"
chmod 644 "$ROOT/opt/dsh/heta-status.mjs"

# 用量出口插件同理（第 ⑬ 步断言它写出来的文件）。
USAGE_PLUGIN="$REPO/app/src/main/assets/heta-usage.mjs"
[ -f "$USAGE_PLUGIN" ] || { echo "FAIL: 缺少用量插件资产 $USAGE_PLUGIN"; exit 1; }
cp -f "$USAGE_PLUGIN" "$ROOT/opt/dsh/heta-usage.mjs"
chmod 644 "$ROOT/opt/dsh/heta-usage.mjs"

echo "⑤ 跑一个真回合（initialize → session/new → prompt → 工具调用）"
# 用 unshare 起一个假 root 命名空间：chroot、/dev、/proc 和 binfmt 都在里面做，不碰宿主。
#
# binfmt 这一步是必须的：bash 工具执行命令时会再 spawn 一个 node 子进程
# （dsh-subprocess-local 的 runner 用 process.execPath），而运行时里的 node 是 aarch64 ELF。
# 没有 binfmt，内核直接 ENOEXEC —— 第一次跑就是卡在这里。注册时用 F 标志（固定解释器 fd），
# 这样 chroot 之后解释器依然可用（chroot 里没有 /usr/bin/qemu-aarch64-static）。
MAGIC=':qemu-aarch64:M::\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\xb7\x00:\xff\xff\xff\xff\xff\xff\xff\x00\xff\xff\xff\xff\xff\xff\xff\xff\xfe\xff\xff\xff:/tmp/qemu-aarch64-static:F'
# 只准备"脚本之外"的东西：binfmt（bash 工具会再 spawn 一个 aarch64 node 子进程，没它 ENOEXEC）。
# /dev、/proc、技能库挂载和 exec chroot 都交给真脚本自己做 —— 那才是 App 实际跑的那条路。
INNER="cp -f '$QEMU' /tmp/qemu-aarch64-static; chmod +x /tmp/qemu-aarch64-static;"
INNER="$INNER mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc 2>/dev/null;"
INNER="$INNER printf '%s' '$MAGIC' > /proc/sys/fs/binfmt_misc/register 2>/dev/null;"
INNER="$INNER exec bash '$TMP/startup.sh'"

python3 "$REPO/scripts/dsh-e2e-acp.py" "$MARKER" -- \
  unshare -rm bash -c "$INNER" 2>&1 | tee "$TMP/acp.log"
acp_status="${PIPESTATUS[0]}"
SESSION_ID="$(grep -m1 '^DSH_E2E_SESSION=' "$TMP/acp.log" | cut -d= -f2-)"
if [ -z "$SESSION_ID" ]; then
  # 拿不到会话 id 就别往下走：`--resume ""` 会静默退回 session/new，
  # 那样第二回合看起来"通过"，其实根本没测续接。
  echo "FAIL: 首回合没拿到 sessionId，无法验证续接"
  exit 1
fi
echo "   首回合会话：$SESSION_ID"

echo "⑥ 第二回合：改人格 → 重启 dsh → session/resume → 再跑一轮"
# 失败要能自解释：这三行分别证明"脚本确实带了 --patch""覆盖层里有我们要的人格"。
echo "   启动脚本里的 patch 实参：$(grep -o -- '--patch [^ ]*' "$TMP/startup.sh" | head -1)"
echo "   覆盖层 system-prompt 段（部署级；预设一开就被 persona 行遮蔽）："
grep -A3 'id: system-prompt' "$ROOT/opt/dsh/heta-run-overlay.patch.yml" | sed 's/^/     /'
echo "   覆盖层 preset persona 段（真正到模型手里的那一段）："
grep -A8 "id: persona" "$ROOT/opt/dsh/heta-run-overlay.patch.yml" | head -12 | sed 's/^/     /'
# 一、把**预设 persona 行**的前缀换掉。
#     为什么不是换部署级那条 `personaPrefix`（`system-prompt` 行上那条）：预设里的 persona 行
#     会**遮蔽**部署级前缀与后缀（dsh-persona 的 README：omitted or empty text shadows the
#     global suffix away；`complete: true` 更是只留前缀）。所以"运行期人格"的真正归属是预设那一行，
#     改部署级那条已经不会到模型手里 —— 这一点下面第 ⑨ 步会连身份句一起断言。
#     人格只在进程启动时读一次，所以"改人格"必须重启 dsh —— 这也正是 App 的真实形态：
#     每一轮 run 都是新进程 + session/resume。
OVERLAY="$ROOT/opt/dsh/heta-run-overlay.patch.yml"
cp -f "$OVERLAY" "$TMP/overlay-round1.yml"
PERSONA2="HETA-E2E-PERSONA-2"
sed -i -E "s|^([[:space:]]+)prefix: You are a coding agent.*|\1prefix: \"$PERSONA2\"|" "$OVERLAY"
grep -q "$PERSONA2" "$OVERLAY" || { echo "FAIL: 覆盖层里没换上第二人格"; exit 1; }

# 二、凭据文件要重新放一份（第一回合的已被脚本 rm -f 掉）。
printf 'export DEEPSEEK_API_KEY=sk-smoke\nexport DEEPSEEK_BASE_URL=http://127.0.0.1:%s\n' "$PORT" > "$CRED"
chmod 600 "$CRED"

ROUND2_PROMPT="第二回合：再写一次标记文件，确认续接之后还在同一个会话里。"
python3 "$REPO/scripts/dsh-e2e-acp.py" "$MARKER" --resume "$SESSION_ID" --prompt "$ROUND2_PROMPT" -- \
  unshare -rm bash -c "$INNER" 2>&1 | tee "$TMP/acp2.log"
round2_status="${PIPESTATUS[0]}"

echo "⑦ 独立证据：chroot 里的 /workspace/smoke.txt"
if [ -f "$ROOT/workspace/smoke.txt" ]; then
  content="$(cat "$ROOT/workspace/smoke.txt")"
  echo "   内容：$content"
  case "$content" in
    *"$MARKER"*) file_ok=1 ;;
    *) file_ok=0 ;;
  esac
else
  echo "   文件不存在"
  file_ok=0
fi

echo "⑧ 独立证据：凭据文件用完必须已经删掉（真脚本的 rm -f）"
if [ -e "$CRED" ]; then
  echo "   还在：$CRED"
  cred_ok=0
else
  echo "   已删除 ✓"
  cred_ok=1
fi

echo "⑨ 续接语义断言（systemPromptUpdate: in-history）"
history_ok=0
python3 - "$MOCK_DUMP" "$PERSONA2" "$ROUND2_PROMPT" <<'PY'
import json
import sys

dump, persona2, round2_prompt = sys.argv[1], sys.argv[2], sys.argv[3]
requests = []
with open(dump, encoding="utf-8") as handle:
    for line in handle:
        line = line.strip()
        if line:
            requests.append(json.loads(line)["body"])


def system_text(body):
    system = body.get("system")
    if isinstance(system, list):
        return " ".join(str(block.get("text") or "") for block in system if isinstance(block, dict))
    return str(system or "")


def user_texts(body):
    texts = []
    for message in body.get("messages") or []:
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, str):
            texts.append(content)
        elif isinstance(content, list):
            for block in content:
                if isinstance(block, dict) and block.get("type") == "text":
                    texts.append(str(block.get("text") or ""))
    return texts


first_prompt = "跑一下冒烟测试：把标记写进文件并打印出来。"
print(f"   假模型共收到 {len(requests)} 次请求")
suffix_marker = "Your working directory is"
# Heta 注入的部署人格：身份句（来自 AgentIdentity）与技能库索引。
# 这两样必须出现在模型看到的 system 里 —— 预设的 persona 行会把部署级后缀整个遮蔽掉，
# 所以它们必须被注入到预设的 persona 行里（见 DshPresetPlane.injectDeploymentSuffix）。
# 实测过没注入时的样子：system 里既没有身份句也没有技能库。
identity_marker = "DeepSeek Harness 编码助手"
skills_marker = "技能库"


def body_text(body):
    return json.dumps(body, ensure_ascii=False)


def persona_location(body):
    """人格标记出现在哪：system 字段，还是历史消息（in-history 增量）。"""
    if persona2 in system_text(body):
        return "system"
    if persona2 in body_text(body):
        return "messages"
    return ""


for index, body in enumerate(requests):
    system = system_text(body)
    print(f"   请求 #{index + 1}：system {len(system)} 字符，"
          f"含我们的后缀={suffix_marker in system}，新人格位置={persona_location(body) or '无'}")
problems = []
missing_identity = [i + 1 for i, body in enumerate(requests) if identity_marker not in system_text(body)]
missing_skills = [i + 1 for i, body in enumerate(requests) if skills_marker not in system_text(body)]
if missing_identity:
    problems.append(
        f"模型看不到 Heta 的身份句（请求 {missing_identity}）—— 部署人格没进预设的 persona 行？"
    )
if missing_skills:
    problems.append(
        f"模型看不到技能库索引（请求 {missing_skills}）—— 技能会永远不被用上"
    )
if len(requests) < 3:
    problems.append(f"请求次数不对（{len(requests)} < 3），第二回合没跑起来")
else:
    round1_hit = persona_location(requests[0])
    round2_hit = persona_location(requests[2])
    if round1_hit:
        problems.append(f"第一回合的请求里就有第二人格（{round1_hit}），断言失去意义")
    if not round2_hit:
        problems.append(
            "续接后模型看不到新人格 —— systemPromptUpdate: in-history 没生效"
            f"（system 开头：{system_text(requests[2])[:200]!r}）"
        )
    else:
        print(f"   ✓ 新人格出现在 {round2_hit}"
              + ("（in-history 增量投递在历史里）" if round2_hit == "messages" else ""))
    round2_users = user_texts(requests[2])
    if first_prompt not in round2_users:
        problems.append("续接后的请求里没有第一回合的用户原文（历史被压成摘要或丢了）")
    if round2_prompt not in round2_users:
        problems.append("续接后的请求里没有本回合的用户原文")
    if len(requests[2].get("messages") or []) <= len(requests[0].get("messages") or []):
        problems.append("续接后的消息条数没有增长，历史看起来没带上")

if problems:
    for problem in problems:
        print(f"   ✗ {problem}")
    sys.exit(1)
print(f"   ✓ 人格已刷新（{persona2}）；历史原样带着，没有退化成摘要")
PY
[ $? -eq 0 ] && history_ok=1

echo "⑩ 计划模式 / 目标模式：换一份带模式意图的覆盖层 → 同一个会话再跑一轮"
# 第 ⑩ 步的覆盖层由单测导出（`DshRuntimeConfigOverlayTest.writesTheModeOverlayForTheEndToEndSmokeTest`）。
# 两份覆盖层只差 join 那一行的 config：计划模式意图 + 一句目标 + 把自动续轮上限压到 1。
MODE_OVERLAY="$(find "$REPO" -name dsh-e2e-overlay-modes.patch.yml -print -quit 2>/dev/null)"
MODE_PROMPT="第三回合：确认计划模式与目标模式都已生效。"
round3_status=1
if [ -z "$MODE_OVERLAY" ] || [ ! -f "$MODE_OVERLAY" ]; then
  echo "   FAIL: 没有模式覆盖层（先跑单测导出：./gradlew :app:testDebugUnitTest）"
else
  cp -f "$MODE_OVERLAY" "$ROOT/opt/dsh/heta-run-overlay.patch.yml"
  # 状态文件先删掉：第 ⑪ 步断言的是**这一轮**写出来的那一份。
  rm -f "$ROOT/root/.dsh/heta-modes.json"
  echo "   覆盖层里的模式意图：$(grep -E '^[[:space:]]+(plan|goal):' "$ROOT/opt/dsh/heta-run-overlay.patch.yml" | tr '\n' ' ')"
  # 凭据文件要重新放一份（上一回合的已被脚本 rm -f 掉）。
  printf 'export DEEPSEEK_API_KEY=sk-smoke\nexport DEEPSEEK_BASE_URL=http://127.0.0.1:%s\n' "$PORT" > "$CRED"
  chmod 600 "$CRED"
  python3 "$REPO/scripts/dsh-e2e-acp.py" "$MARKER" --resume "$SESSION_ID" --prompt "$MODE_PROMPT" -- \
    unshare -rm bash -c "$INNER" 2>&1 | tee "$TMP/acp3.log"
  round3_status="${PIPESTATUS[0]}"
fi

echo "⑪ 模式断言：模型收到的请求里有计划模式那一段（续接的会话按 in-history 投递）；/plan 与 /goal 都是 success"
mode_ok=0
MODE_STATUS="$ROOT/root/.dsh/heta-modes.json"
python3 - "$MOCK_DUMP" "$ROUND2_PROMPT" "$MODE_STATUS" "$MODE_PROMPT" <<'PY'
import json
import sys

dump, round2_prompt, status_path, mode_prompt = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
requests = []
with open(dump, encoding="utf-8") as handle:
    for line in handle:
        line = line.strip()
        if line:
            requests.append(json.loads(line)["body"])


def system_text(body):
    system = body.get("system")
    if isinstance(system, list):
        return " ".join(str(block.get("text") or "") for block in system if isinstance(block, dict))
    return str(system or "")


def blob(body):
    return json.dumps(body, ensure_ascii=False)


def plan_location(body):
    """计划模式那一段出现在哪：system 字段，还是历史消息（续接的 in-history 增量）。"""
    if plan_marker in system_text(body):
        return "system"
    if plan_marker in blob(body):
        return "messages"
    return ""


plan_marker = "You are in plan mode"
round_marker = "<goal_round>"
plan_hits = [index + 1 for index, body in enumerate(requests) if plan_location(body)]
round_hits = [index + 1 for index, body in enumerate(requests) if round_marker in blob(body)]
# "第二回合的请求" = 还没出现第三回合提示语的那些：续接的请求里本来就带着第二回合的
# 用户原文（历史），拿它判会把自己也判进去。
round3_start = next(
    (index for index, body in enumerate(requests) if mode_prompt in blob(body)),
    len(requests),
)
stale = [index + 1 for index, body in enumerate(requests[:round3_start]) if plan_location(body)]

try:
    with open(status_path, encoding="utf-8") as handle:
        status = json.load(handle)
except OSError as error:
    status = None
    print(f"   状态文件读不到（{status_path}）：{error}")

lines = [] if status is None else (status.get("lines") or [])
plan_line = next((entry for entry in lines if entry.get("line") == "/plan"), None)
goal_line = next((entry for entry in lines if str(entry.get("line", "")).startswith("/goal ")), None)

print(f"   请求总数 {len(requests)}；带计划模式段的有 {plan_hits}")
print(f"   自动续轮（<goal_round>）出现在请求 {round_hits or '（没有）'}"
      " —— dsh 在 resume 之后会把目标重新置为 disarmed，这是官方形态，不算失败")
print(f"   状态文件：plan={plan_line} goal={'（有）' if goal_line else '（没有）'}")

problems = []
if not plan_hits:
    problems.append(
        "模型收到的请求里没有「You are in plan mode」—— /plan 没真的执行"
        "（ACP 没有命令通道，得靠 join 那一跳按 agent 去预设的隔离域里取 planMode 再补 commands.execute）"
    )
if stale:
    problems.append(f"计划模式那一段出现在第二回合的请求 {stale} 里 —— 断言失去意义")
if status is None:
    problems.append(f"join 插件没写出状态文件 {status_path}（那一跳根本没跑？）")
else:
    if plan_line is None:
        problems.append("状态文件里没有 /plan 这一条 —— 计划模式的意图没被执行")
    elif plan_line.get("kind") != "success":
        problems.append(f"/plan 执行结果不是 success：{plan_line}")
    elif not plan_line.get("hit"):
        problems.append(f"/plan 没有命中命令（这份预设没注册它）：{plan_line}")
    if goal_line is None:
        problems.append("状态文件里没有 /goal 这一条 —— 目标模式的意图没被执行")
    elif goal_line.get("kind") != "success":
        problems.append(f"/goal 执行结果不是 success：{goal_line}")
    elif "Goal created" not in str(goal_line.get("text") or ""):
        problems.append(f"dsh 回的文本里没有 Goal created（目标没真建起来）：{goal_line}")

if problems:
    for problem in problems:
        print(f"   ✗ {problem}")
    sys.exit(1)
location = plan_location(requests[plan_hits[0] - 1])
print(f"   ✓ 计划模式进了模型的请求 {plan_hits}（位置：{location}）；"
      "/plan 与 /goal 都被 dsh 回了 success")
PY
[ $? -eq 0 ] && mode_ok=1

echo
# ⑫ 状态出口插件：会话进程要把**活的**插件清单写成文件（App 读它，不再为看一眼清单再起一个 dsh）。
#    断言的是"文件在、协议前缀对、entries 非空、不是超时快照"——内容与探针那份同源，不在这里重复比。
INVENTORY_FILE="$ROOT/root/.dsh/heta-inventory.json"
inv_ok=0
if [ -f "$INVENTORY_FILE" ]; then
  inv_ok="$(python3 - "$INVENTORY_FILE" <<'PY'
import json, sys
marker = "HETA-INVENTORY-JSON:"
try:
    text = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    print(0); raise SystemExit
if marker not in text:
    print(0); raise SystemExit
try:
    data = json.loads(text.split(marker, 1)[1].splitlines()[0])
except ValueError:
    print(0); raise SystemExit
entries = data.get("entries") or []
print(1 if len(entries) > 1 and data.get("timedOut") is False else 0)
PY
)"
  echo "⑫ 状态出口插件：$INVENTORY_FILE（$(wc -c < "$INVENTORY_FILE") 字节，断言=${inv_ok:-0}）"
else
  echo "⑫ 状态出口插件：$INVENTORY_FILE 不存在 ✗"
fi

# 还要有 runtime 目录里那一份：App 直接读它（不用 su、不用起进程）—— 「秒开」靠的就是它。
SHARED_INVENTORY="$ROOT/opt/dsh/heta-inventory.json"
if [ -f "$SHARED_INVENTORY" ] && head -c 32 "$SHARED_INVENTORY" | grep -q "HETA-INVENTORY-JSON:"; then
  echo "⑫b runtime 目录那一份：$SHARED_INVENTORY（$(wc -c < "$SHARED_INVENTORY") 字节）✓"
else
  echo "⑫b runtime 目录那一份不存在或没有协议前缀 ✗（$SHARED_INVENTORY）"
  inv_ok=0
fi

echo
# ⑬ 用量出口插件：每一轮的账（轮次 / 步数 / token / cache）也要写成文件 —— App 在回复下面
#    显示的就是它。这里断言：文件在、协议前缀对、至少有一轮、且轮次是正数。
#    注意这一轮跑的是**假 LLM**，它不一定报 usage：所以 token 数字不在这里断言（那属于模型的事），
#    这里钉的是"轮次 / 步数这条路通"。
USAGE_FILE="$ROOT/root/.dsh/heta-usage.json"
usage_ok=0
if [ -f "$USAGE_FILE" ]; then
  usage_ok="$(python3 - "$USAGE_FILE" <<'PY'
import json, sys
marker = "HETA-USAGE-JSON:"
try:
    text = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    print(0); raise SystemExit
if marker not in text:
    print(0); raise SystemExit
try:
    data = json.loads(text.split(marker, 1)[1].splitlines()[0])
except ValueError:
    print(0); raise SystemExit
turns = [t for s in (data.get("sessions") or []) for t in (s.get("turns") or [])]
ok = bool(turns) and all((t.get("turn") or 0) > 0 for t in turns)
# 时间口径也要有：这一步的模型用时是从 step/start 到 assistant/message 落定的差，
# 真跑一轮必然大于 0（假 LLM 也是真等了一会儿）。
# 时间口径也要有。注意：文件里可能还留着**上一版插件**写的老会话（那一轮没有 llmMs），
# 所以这里只要求"至少有一轮带时间"——这一轮跑的肯定是新版插件写的。
ok = ok and any((t.get("llmMs") or 0) > 0 for t in turns)
print(1 if ok else 0)
PY
)"
  echo "⑬ 用量出口插件：$USAGE_FILE（$(wc -c < "$USAGE_FILE") 字节，断言=${usage_ok:-0}）"
else
  echo "⑬ 用量出口插件：$USAGE_FILE 不存在 ✗"
fi

# 上下文那两块（占用 / 窗口 + 系统提示词 / 工具定义 / 对话消息）来自 sessionProjections 投影：
# 这一份安装里有没有那个投影，这里直接打出来 —— 没有也不算失败（插件会照旧写别的）。
if [ -f "$USAGE_FILE" ]; then
  python3 - "$USAGE_FILE" <<'PY'
import json, sys
marker = "HETA-USAGE-JSON:"
text = open(sys.argv[1], encoding="utf-8").read()
data = json.loads(text.split(marker, 1)[1].splitlines()[0])
for session in data.get("sessions") or []:
    totals = session.get("totals") or {}
    context = session.get("context")
    print("    会话 %s：totals=%s" % ((session.get("id") or "")[:8], json.dumps(totals, ensure_ascii=False)))
    print("    上下文投影：%s" % ("（没有这个投影）" if context is None else json.dumps(context, ensure_ascii=False)))
PY
fi

SHARED_USAGE="$ROOT/opt/dsh/heta-usage.json"
if [ -f "$SHARED_USAGE" ] && head -c 24 "$SHARED_USAGE" | grep -q "HETA-USAGE-JSON:"; then
  echo "⑬b runtime 目录那一份：$SHARED_USAGE（$(wc -c < "$SHARED_USAGE") 字节）✓"
else
  echo "⑬b runtime 目录那一份不存在或没有协议前缀 ✗（$SHARED_USAGE）"
  usage_ok=0
fi

echo
if [ "$acp_status" -eq 0 ] && [ "$round2_status" -eq 0 ] && [ "$file_ok" -eq 1 ] \
   && [ "$cred_ok" -eq 1 ] && [ "$history_ok" -eq 1 ] && [ "$round3_status" -eq 0 ] \
   && [ "$mode_ok" -eq 1 ] && [ "${inv_ok:-0}" -eq 1 ] && [ "${usage_ok:-0}" -eq 1 ]; then
  echo "====> 端到端通过：工具真执行、回合正常收尾、续接后人格已刷新且历史原样、计划与目标模式都真的生效、活清单与用量都被写出来"
  exit 0
fi
echo "====> 端到端失败（首回合=$acp_status 续接=$round2_status 文件=$file_ok 凭据=$cred_ok 续接语义=$history_ok 模式回合=$round3_status 模式=$mode_ok 活清单=${inv_ok:-0}）"
if [ "$round3_status" != 0 ]; then
  echo "--- 模式回合日志尾部 ---"; tail -30 "$TMP/acp3.log" 2>/dev/null
fi
echo "--- 假模型日志 ---"; tail -20 "$TMP/mock.log"
exit 1
