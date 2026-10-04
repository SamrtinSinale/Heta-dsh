#!/bin/bash
#
# 「活清单」探针的端到端冒烟：在 qemu + chroot 里起一次真运行时，让桥打一行 JSON，然后断言
# 那份清单是完整的。
#
# 为什么这条测试必须存在：App 的「扩展」页面在有活清单时**按它渲染**（模块名 / 配置状态 /
# 运行状态），而那份数据是 dsh 进程里的 Loader 投影 —— 单测只能证明"我的解析器认得这个形状"，
# 证明不了"dsh 真的会打这个形状"。桥挂不上、覆盖层写坏、`--expose-internals` 掉了、
# 桥的稳定判据在慢机器上踩线 —— 这四种都只有真跑一次才看得见。
#
# 为什么跑的是**导出的那份脚本**而不是这里现拼一条命令：
# `scripts/test-dsh-e2e.sh` 的头一条教训就是"端到端不自己拼启动命令 —— 拼就会漏掉真实路径"。
# 所以这里解包运行时、把桥资产拷进去，然后执行单测导出的 `app/build/dsh-probe.sh`
#（`DshInventoryProbeScriptTest.writesTheProbeScriptForTheLiveInventoryCiRun` 写的），
# 把本次解包的 root 作为脚本的 $1 传进去。脚本自己会写取清单用的覆盖层、挂 /dev 与 /proc、
# 最后 exec chroot —— App 里跑的就是同一份文本。
#
# 前置：先跑过 `./gradlew :app:testDebugUnitTest`（导出 app/build/dsh-probe.sh）。
#
# 用法：sudo bash scripts/test-dsh-live-inventory.sh
#   QEMU_AARCH64        指定 qemu-aarch64-static（默认取 PATH 或 /usr/bin）
#   DSH_LIVE_PROBE      指定探针脚本（默认取 app/build/dsh-probe.sh）
#   DSH_LIVE_ROOT       复用已经解包好的运行时目录（默认自己解包到临时目录）
#   DSH_LIVE_KEEP       非空则保留临时目录（排查用）
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARKER="HETA-INVENTORY-JSON:"
# 条数下限：**实测 98**（acp profile，真 arm64 + qemu；Windows 上的 x86_64 node 也是 98）。
# 为什么不写 186：那是桌面 profile 的数，ACP profile 就是 98。为什么不写 100：
# 平台相关的开关会换行（linux 上 bash 那两条启用、pwsh 那两条禁用，条数几乎不变），
# 留一点余量是防"某个平台条件行少一条"，不是防清单整体塌掉 —— 后者由"必须能解析 +
# 桥自己必须在列表里 + 无重复"这三条兜着。
MIN_ENTRIES="${DSH_LIVE_MIN_ENTRIES:-90}"

if [ "$(id -u)" != 0 ]; then
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    exec sudo -E bash "$0" "$@"
  fi
  echo "SKIP: 活清单冒烟需要 root（要 chroot + 挂载 /dev 与 /proc）"
  exit 77
fi

QEMU="${QEMU_AARCH64:-$(command -v qemu-aarch64-static || true)}"
[ -n "$QEMU" ] && [ -x "$QEMU" ] || { echo "SKIP: 找不到 qemu-aarch64-static（apt-get install qemu-user-static）"; exit 77; }

ASSET="$REPO/app/src/main/assets/dsh-runtime.tar.xz"
[ -f "$ASSET" ] || { echo "FAIL: 缺少运行时资产 $ASSET"; exit 1; }

BRIDGE="$REPO/app/src/main/assets/heta-inventory-bridge.mjs"
[ -f "$BRIDGE" ] || { echo "FAIL: 缺少桥插件资产 $BRIDGE"; exit 1; }

SCRIPT="${DSH_LIVE_PROBE:-$REPO/app/build/dsh-probe.sh}"
if [ ! -f "$SCRIPT" ]; then
  echo "FAIL: 没有单测导出的探针脚本 $SCRIPT"
  echo "      先跑 ./gradlew :app:testDebugUnitTest（或 ./gradlew :app:testDebugUnitTest --tests '*DshInventoryProbeScriptTest*'）"
  exit 1
fi

TMP="$(mktemp -d /tmp/dsh-live.XXXXXX)"
cleanup() {
  # 和运行时脚本同一套匹配：mountinfo 里记的是解析后的路径，且必须是路径边界。
  awk '{print $5}' /proc/self/mountinfo 2>/dev/null | while read -r m; do
    cm=$(readlink -f "$m" 2>/dev/null || echo "$m")
    case "$cm" in "$TMP"|"$TMP"/*) umount -l "$m" 2>/dev/null ;; esac
  done
  [ -z "${DSH_LIVE_KEEP:-}" ] && rm -rf "$TMP"
}
trap cleanup EXIT

if [ -n "${DSH_LIVE_ROOT:-}" ]; then
  ROOT="$DSH_LIVE_ROOT"
else
  ROOT="$TMP/root"
  echo "① 解包运行时资产 → $ROOT"
  mkdir -p "$ROOT"
  tar -xJf "$ASSET" -C "$ROOT" || { echo "FAIL: 解包失败"; exit 1; }
fi

echo "② 把桥资产放进 <root>/opt/dsh/（覆盖层里的相对名就是按这个目录解析的）"
mkdir -p "$ROOT/opt/dsh"
cp -f "$BRIDGE" "$ROOT/opt/dsh/heta-inventory-bridge.mjs" || { echo "FAIL: 桥拷不进去"; exit 1; }
# 预设那一跳的插件也在 root 里（覆盖层里是相对名）。探针里它不会被触发，但资产缺席会让
# "真启动有的东西探针没有"成为一套静默差异，所以照拷。
JOIN="$REPO/app/src/main/assets/heta-preset-join.mjs"
[ -f "$JOIN" ] && cp -f "$JOIN" "$ROOT/opt/dsh/heta-preset-join.mjs"

OUT="$TMP/probe.out"
ERR="$TMP/probe.err"

# binfmt 这一步是必须的：脚本最后的 `exec chroot ... /opt/node/bin/node` 要跑 aarch64 ELF，
# 而这个 chroot 里没有 qemu —— 只有内核的 binfmt_misc 能救。注册时用 F 标志（固定解释器 fd），
# chroot 之后解释器依然可用。同 scripts/test-dsh-e2e.sh。
MAGIC=':qemu-aarch64:M::\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\xb7\x00:\xff\xff\xff\xff\xff\xff\xff\x00\xff\xff\xff\xff\xff\xff\xff\xff\xfe\xff\xff\xff:/tmp/qemu-aarch64-static:F'
# 只准备"脚本之外"的东西：binfmt。/dev、/proc 与 exec chroot 都交给导出的探针脚本自己做
# —— 那才是 App 实际跑的那条路。
# 把 qemu 与探针脚本**都拷进 $TMP** 再跑：unshare 出来的命名空间里，仓库与 $HOME 下的路径
# 可能不可达（实测会 "Permission denied" → bash 退出码 126）。e2e 那条脚本就是这么干的
# （`exec bash '$TMP/startup.sh'`），这里照抄那个做法。
STAGED_QEMU="$TMP/qemu-aarch64-static"
STAGED_SCRIPT="$TMP/probe.sh"
cp -f "$QEMU" "$STAGED_QEMU"; chmod 755 "$STAGED_QEMU"
cp -f "$SCRIPT" "$STAGED_SCRIPT"; chmod 644 "$STAGED_SCRIPT"

INNER="cp -f '$STAGED_QEMU' /tmp/qemu-aarch64-static; chmod +x /tmp/qemu-aarch64-static;"
INNER="$INNER mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc 2>/dev/null;"
INNER="$INNER printf '%s' '$MAGIC' > /proc/sys/fs/binfmt_misc/register 2>/dev/null;"
INNER="$INNER exec bash '$STAGED_SCRIPT' '$ROOT'"

echo "③ 跑导出的探针脚本（root=$ROOT）"
echo "   脚本：$SCRIPT"
echo "   起 dsh 的那一行：$(grep -m1 '^exec chroot' "$SCRIPT" || echo '（脚本里没有 exec chroot —— 已经不对了）')"
# 用 unshare 起一个私有的 mount 命名空间：chroot、/dev、/proc、binfmt 都在里面做，不碰宿主。
# 这里刻意**不**开 `set -e`：下游每条断言都要自己把"哪一步、看到什么"打出来再决定退不退，
# 半路静默退出只会留下一句"退出码 1"。
unshare -rm bash -c "$INNER" > "$OUT" 2> "$ERR"
status=$?
echo "   退出码：$status"
# 模拟器下这一步是分钟级的（实测真 arm64 + qemu 全程 27 秒），所以不提"慢了就是坏"。

echo "④ 断言"
python3 - "$OUT" "$ERR" "$status" "$MARKER" "$MIN_ENTRIES" <<'PY'
import json
import sys

out_path, err_path, status, marker, min_entries = sys.argv[1:6]
min_entries = int(min_entries)
stdout = open(out_path, encoding="utf-8", errors="replace").read()
stderr = open(err_path, encoding="utf-8", errors="replace").read()


def fail(*lines):
    for line in lines:
        print("   " + line)
    print("---- stderr 尾部 ----")
    print("\n".join(stderr.splitlines()[-25:]))
    print("---- stdout 头部 ----")
    print("\n".join(stdout.splitlines()[:3]))
    sys.exit(1)


if int(status) != 0:
    fail(f"✗ 探针脚本退出码 {status}（期望 0）")

lines = [line for line in stdout.splitlines() if marker in line]
if not lines:
    fail(f"✗ stdout 里没有 {marker} 那一行")
if len(lines) > 1:
    fail(f"✗ marker 行出现了 {len(lines)} 次（只该有一行）")

payload = json.loads(lines[0].split(marker, 1)[1])
entries = payload.get("entries")
if not isinstance(entries, list):
    fail(f"✗ JSON 里没有 entries 数组：{sorted(payload)}")
if payload.get("timedOut"):
    fail("✗ 桥报的是超时快照（timedOut=true）—— dsh 没在桥的窗口内挂完，清单不完整")
if payload.get("error"):
    fail(f"✗ 桥报错：{payload['error']}")

ids = [entry.get("entryId") for entry in entries]
duplicates = sorted({i for i in ids if ids.count(i) > 1})
if duplicates:
    fail(f"✗ entryId 有重复：{duplicates[:10]}")
if any(not i for i in ids):
    fail("✗ 有条目没有 entryId")

print(f"   ✓ marker 一行、JSON 可解析、entryId 无重复（{len(entries)} 条）")
if len(entries) < min_entries:
    fail(f"✗ 条目只有 {len(entries)} 条，低于下限 {min_entries} —— 清单看起来塌了")

phases = {}
for entry in entries:
    phases[entry.get("fiberPhase")] = phases.get(entry.get("fiberPhase"), 0) + 1
bridge = [e for e in entries if e.get("entryId", "").endswith("heta-inventory-bridge")]
if not bridge:
    fail("✗ 清单里没有桥自己（include:heta-inventory-bridge）—— 说明 insert 没生效，"
         "这一份多半是别的 dsh 打的")
print(f"   ✓ 桥自己在清单里且 {bridge[0].get('fiberPhase')}：{bridge[0].get('moduleName')}")
presets = payload.get("agentPresets")
print(f"   ✓ hasPresets={payload.get('hasPresets')}　agentPresets={len(presets) if presets else 0} 个")
print(f"   ✓ fiberPhase 分布 {phases}")
print(f"   ✓ 前 3 条：{[e.get('entryId') for e in entries[:3]]}")
print(f"   ✓ entries={len(entries)}（下限 {min_entries}）")
PY
assert_status=$?
if [ "$assert_status" -ne 0 ]; then
  echo "====> 活清单冒烟失败"
  exit 1
fi

echo "====> 活清单冒烟通过：dsh 真被起了一次，桥真被打出来了，清单完整且无重复"
