#!/bin/bash
#
# 本地跑**真 arm64 运行时**（不用 CI、不用手机）。
#
# 为什么需要：随包运行时是 arm64，本机是 x86_64，所以"运行时里到底发生了什么"一直只能靠 CI
# （qemu + 真 chroot）或真机来验 —— 一轮十分钟，且没法边改边试。这里用**静态 qemu** +
# 一份全量解包的运行时，把这件事搬回本机（纯模拟跑一次 dsh 约 30 秒）。
#
# 为什么用静态 qemu：Ubuntu 26.04 的 `qemu-user-static` 只剩个壳（二进制被拆走），
# chroot 里要用的必须是**静态链接**的那份，所以从 Debian 池取（只解 .deb 拿二进制，不安装）。
#
# 用法：
#   bash scripts/local-arm64-runtime.sh                       # 只跑 node --version 自检
#   bash scripts/local-arm64-runtime.sh -- <chroot 里的命令…>   # 跑你自己的命令
# 环境变量：
#   HETA_LOCAL_ROOT   解包目录（默认 ~/heta-realroot）
#   HETA_LOCAL_QEMU   静态 qemu-aarch64-static 的路径（默认 ~/qemu-static/qemu-aarch64-static）
#   HETA_GUEST_PATH   chroot 里的 PATH（默认与 DshRuntimeConfig.PATH_IN_ROOT 一致）
#
# 没有网络、拿不到 qemu 时**跳过**（exit 77），不假装通过。
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET="$REPO/app/src/main/assets/dsh-runtime.tar.xz"
ROOT="${HETA_LOCAL_ROOT:-$HOME/heta-realroot}"
QEMU="${HETA_LOCAL_QEMU:-$HOME/qemu-static/qemu-aarch64-static}"
GUEST_PATH="${HETA_GUEST_PATH:-/system/bin:/system/xbin:/product/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"

[ -f "$ASSET" ] || { echo "SKIP: 找不到运行时资产 $ASSET"; exit 77; }

fetch_qemu() {
  command -v curl >/dev/null 2>&1 || return 1
  local work deb pool found
  work="$(dirname "$QEMU")"
  mkdir -p "$work" || return 1
  for pool in "http://archive.ubuntu.com/ubuntu/pool/universe/q/qemu/" \
              "http://deb.debian.org/debian/pool/main/q/qemu/"; do
    # 候选按版本从新到旧；**逐个试**，只接受 `file` 明确说是静态链接的那一个。
    # （踩过两次：Debian 的 qemu-user-static 里是悬空符号链接；Ubuntu 老版本 qemu-user 是动态链接，
    #   动态的进不了 chroot —— 所以这里不看包名、只看二进制本身。）
    for deb in $(curl -fsSL "$pool" | grep -oE 'qemu-user(_static)?_[^"]+_amd64\.deb' | sort -Vr); do
      curl -fsSL -o "$work/$deb" "$pool$deb" || continue
      rm -rf "$work/extract"
      dpkg-deb -x "$work/$deb" "$work/extract" 2>/dev/null || continue
      found="$(find "$work/extract" -type f -name 'qemu-aarch64' | head -1)"
      [ -n "$found" ] || continue
      if file "$found" | grep -qi 'static'; then
        echo "取 qemu：$deb（$(file -b "$found" | cut -c1-40)…）" >&2
        cp -f "$found" "$QEMU" && chmod 755 "$QEMU" && return 0
      fi
    done
  done
  return 1
}

[ -x "$QEMU" ] || fetch_qemu || { echo "SKIP: 拿不到静态 qemu（没网？）—— 设 HETA_LOCAL_QEMU 指一份静态 qemu-aarch64-static"; exit 77; }

if [ ! -x "$ROOT/opt/node/bin/node" ]; then
  echo "解包运行时到 $ROOT（约 265 MB，一次性）" >&2
  rm -rf "$ROOT"; mkdir -p "$ROOT"
  tar -xJf "$ASSET" -C "$ROOT" || { echo "FAIL: 解包失败"; exit 1; }
fi
cp -f "$QEMU" "$ROOT/qemu-aarch64-static"
chmod 755 "$ROOT/qemu-aarch64-static"

# /proc 与 /dev：与启动脚本同一套条件（缺了 node/子进程会起不来）。失败不阻断（可能已经挂过）。
[ -e "$ROOT/proc/self" ] || mount -t proc proc "$ROOT/proc" 2>/dev/null || true
[ -e "$ROOT/dev/pts/ptmx" ] || mount --rbind /dev "$ROOT/dev" 2>/dev/null || true

if [ "${1:-}" = "--" ]; then
  shift
  env -i HOME=/root PATH="$GUEST_PATH" LANG=C.UTF-8 DSH_HOME=/root/.dsh \
    chroot "$ROOT" /qemu-aarch64-static "$@"
else
  echo "自检：chroot 里的 arm64 node" >&2
  env -i HOME=/root PATH="$GUEST_PATH" LANG=C.UTF-8 DSH_HOME=/root/.dsh \
    chroot "$ROOT" /qemu-aarch64-static /opt/node/bin/node --version
fi
