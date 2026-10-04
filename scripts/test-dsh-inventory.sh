#!/bin/bash
#
# 准备"dsh 自己算出来的组合结果"，给单测做交叉验证
# （`DshPluginInventoryTest.matchesTheRuntimesOwnComposedTree`）。
#
# 为什么不用 qemu：`--dump-config` 只读各层补丁文件、组合条目，**不挂载插件、也不求值 `!!js`**
# （官方 dump-config 模块的说明），所以它对 CPU 架构没有要求 —— 用 runner 自己的 node 就行。
# 这也意味着这条交叉验证是**离线**的：不联网、不 chroot、不装东西。
#
# 为什么非要有它：Kotlin 侧的层序/合并规则是从 dsh 源码里抄的，单测只能证明"抄得自洽"，
# 只有和 dsh 自己算出来的东西逐行对上，才证明"抄对了"。
#
# stdout 只输出两行（直接 `>> $GITHUB_ENV` 用），其余都走 stderr：
#   HETA_DUMP_ROOT=<解包出来的运行时根目录>
#   HETA_DUMP_YAML=<dump 出来的组合结果>
#
# 没有 node 时**跳过**（exit 0）并大声说明 —— 单测那边会跟着跳过，不会假装通过。
#
# 本机自测（Windows 的 node）：NODE="/mnt/c/Program Files/nodejs/node.exe" \
#   WORK_ROOT=/mnt/e/tmp bash scripts/test-dsh-inventory.sh
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 1

NODE_BIN="${NODE:-node}"
WORK="${WORK_ROOT:-$(mktemp -d)}"

if ! command -v "$NODE_BIN" >/dev/null 2>&1 && [ ! -x "$NODE_BIN" ]; then
  echo "SKIP: 找不到 node（$NODE_BIN）—— 交叉验证将跳过，不是通过" >&2
  exit 0
fi

mkdir -p "$WORK"
echo "解包运行时资产里的 opt/dsh → $WORK" >&2
tar -xJf app/src/main/assets/dsh-runtime.tar.xz -C "$WORK" ./opt/dsh || {
  echo "解包失败" >&2
  exit 1
}

# 布局要和 chroot 里一致：HOME=/root、DSH_HOME=/root/.dsh ⇒ profile 落在
# <root>/root/.dsh/profiles/acp —— 正是 DshPluginInventory 读的那个位置。
export DSH_HOME="$WORK/root/.dsh"
BIN="$WORK/opt/dsh/lib/bin.js"

# 造出 profile / home / 覆盖层这三层，让交叉验证真的比到"层序 + 逐字段合并"这件事上 ——
# 默认 profile 的用户层是空的（模板就是注释 + `[]`），那种情况下比不出任何顺序问题。
# 每一行都挑的是随包运行时里确实存在的 id；哪一层该赢，见下面注释。
PROFILE_DIR="$DSH_HOME/profiles/acp"
mkdir -p "$PROFILE_DIR"
cat > "$PROFILE_DIR/package.json" <<'JSON'
{
  "name": "dsh-profile-acp",
  "private": true,
  "dependencies": {},
  "dsh": { "profile": { "bundles": ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-acp-app"] } }
}
JSON
# profile 层：把 base 用 !!js 关掉的 hmr 打开；把 base 关掉的 tool-ralph 也打开（下一层会再关掉）。
cat > "$PROFILE_DIR/cordis.patch.yml" <<'YAML'
- id: hmr
  disabled: false
- id: tool-ralph
  disabled: false
YAML
# home 层（在 profile 之后生效）：重新关掉 tool-ralph；再对 plugin-manager 只提 config
# （它只该算"提到过"，不能把上一层的 disabled 当成"启用"）。
cat > "$WORK/root/.dsh/cordis.patch.yml" <<'YAML'
- id: tool-ralph
  disabled: true
- id: plugin-manager
  config:
    probe: 1
YAML
# Heta 覆盖层（最后生效，等于 App 每次运行时传的 --patch）：打开 session-title-llm。
OVERLAY="$WORK/opt/dsh/heta-run-overlay.patch.yml"
cat > "$OVERLAY" <<'YAML'
- id: session-title-llm
  disabled: false
YAML

# Windows 的 node 只认 Windows 路径（只在本机自测时走这个分支；CI 上是 Linux 的 node）。
WIN_NODE="$(wslpath -w "$NODE_BIN")"
WIN_HOME="$(wslpath -w "$DSH_HOME")"
WIN_BIN="$(wslpath -w "$BIN")"
WIN_OVERLAY="$(wslpath -w "$OVERLAY")"

echo "跑 dsh 自己的 --dump-config（带 --patch 覆盖层）…" >&2
if [[ "$NODE_BIN" == *.exe ]]; then
  # 本机自测分支。Windows 的 node 从 WSL 调起时，**Windows 那边的全局环境**会盖掉传下去的
  # DSH_HOME（实测：传 `E:\probe-home` 进去，进程里看到的是 `C:\Users\<用户>\.dsh`），
  # 于是那份组合结果会落到真实用户目录里 —— 不行。所以先写一个 .cmd，用 cmd 的 set 来定。
  WRAPPER="$WORK/dump.cmd"
  {
    echo '@echo off'
    echo "set DSH_HOME=$WIN_HOME"
    echo "\"$WIN_NODE\" \"$WIN_BIN\" --profile acp --patch \"$WIN_OVERLAY\" --dump-config"
  } > "$WRAPPER"
  ( cd "$WORK" && cmd.exe /c "$(wslpath -w "$WRAPPER")" ) > "$WORK/dump.yml"
  status=$?
else
  DSH_HOME="$DSH_HOME" "$NODE_BIN" "$BIN" --profile acp --patch "$OVERLAY" --dump-config > "$WORK/dump.yml"
  status=$?
fi
if [ "$status" -ne 0 ]; then
  echo "dsh --dump-config 失败（exit=$status），见上面的输出" >&2
  exit 1
fi

# 三层覆盖必须真的生效，否则这次交叉验证等于没比（dsh 对"没匹配到行"的补丁只给警告）。
for probe in 'id: hmr' 'id: tool-ralph' 'id: session-title-llm'; do
  if ! grep -q "^- $probe\$" "$WORK/dump.yml"; then
    echo "dump 里没有目标行（$probe）—— 覆盖没生效，这次交叉验证没有意义" >&2
    exit 1
  fi
done

rows=$(grep -c '^- id:' "$WORK/dump.yml" || true)
sections=$(grep -c '^# ==' "$WORK/dump.yml" || true)
echo "dump 里 $rows 行插件、$sections 个来源段" >&2
if [ "$rows" -lt 10 ]; then
  echo "dump 只有 $rows 行，看起来不对（期望几十行）—— 宁可不给变量，也别让单测比一份空表" >&2
  exit 1
fi

echo "HETA_DUMP_ROOT=$WORK"
echo "HETA_DUMP_YAML=$WORK/dump.yml"
