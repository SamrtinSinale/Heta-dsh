#!/usr/bin/env python3
"""Android 字符串资源的两条硬约束（都被真机上/CI 上红过）。

1. **不许有未转义的 ASCII 撇号**：aapt2 的 flattener 会报
   `Failed to flatten XML for resource 'x' with error: Invalid unicode escape`，
   而且它是在 `mergeDebugResources` 才炸 —— 只跑 Kotlin 编译的本地闸门看不见。
   要用 `\'` 或者跟官方文案一样用 U+2019 `’`。
2. **三份 strings.xml 的键集必须一致**：漏一个键不会编译失败（只是回落到英文），
   但用户会在那一种语言里看到英文，属于"没人会发现"的那类问题。

用法：python3 scripts/check-android-strings.py [仓库根]
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent)
FILES = {
    "en": REPO / "app/src/main/res/values/strings.xml",
    "zh-Hans": REPO / "app/src/main/res/values-b+zh+Hans/strings.xml",
    "zh-Hant": REPO / "app/src/main/res/values-b+zh+Hant/strings.xml",
}
# 合法的转义序列（资源 flattener 认得这些）；其余反斜杠都算可疑。
LEGAL_ESCAPES = re.compile(r"\\(n|t|'|\"|@|\?|u[0-9a-fA-F]{4})")

problems: list[str] = []
keysets: dict[str, set[str]] = {}

for label, path in FILES.items():
    if not path.is_file():
        problems.append(f"{label}: 找不到 {path}")
        continue
    text = path.read_text(encoding="utf-8")
    root = ET.parse(path).getroot()
    keys: set[str] = set()
    for element in root.findall("string"):
        name = element.get("name") or ""
        if name in keys:
            problems.append(f"{label}: 键重复 {name}")
        keys.add(name)
        # 元素原始文本（含转义原样）
        raw = (element.text or "")
        # 先去掉合法转义，再看还剩哪些反斜杠
        stripped = LEGAL_ESCAPES.sub("", raw)
        if "\\" in stripped:
            problems.append(f"{label}: {name} 里有不认识的反斜杠转义：{raw[:60]!r}")
        # 未转义的 ASCII 撇号
        cleaned = re.sub(r"\\.", "", raw)
        if "'" in cleaned:
            problems.append(f"{label}: {name} 里有未转义的 ASCII 撇号（aapt2 会报 Invalid unicode escape）：{raw[:60]!r}")
    keysets[label] = keys

# 故意保持英文的键：`LocaleResourcesTest.supplementalUiResourcesFollowLocaleWhileTechnicalTermsStayEnglish`
# 对 en/zh-CN/zh-TW/fr-FR 都断言它们是 "Skills" / "API Key"，实现方式就是 zh 文件里**不写**这两个键
# （靠 Android 的回落）。所以"键集必须一致"这条要放过它们 —— 否则会把刻意的设计报成漏译。
INTENTIONALLY_ENGLISH = {"route_skills", "speech_api_key"}

if len(keysets) == len(FILES):
    base_label = "en"
    base = keysets[base_label]
    for label, keys in keysets.items():
        if label == base_label:
            continue
        missing = sorted(base - keys - INTENTIONALLY_ENGLISH)
        extra = sorted(keys - base - INTENTIONALLY_ENGLISH)
        if missing:
            problems.append(f"{label} 缺少 {len(missing)} 个键（会显示英文）：{missing[:8]}")
        if extra:
            problems.append(f"{label} 多出 {len(extra)} 个键：{extra[:8]}")

if problems:
    print("✗ Android 字符串资源检查没过：")
    for problem in problems:
        print(f"   - {problem}")
    sys.exit(1)
print(
    f"✓ 三份 strings.xml：无未转义撇号、无异常转义、键集一致（各 {len(keysets['en'])} 个键；"
    f"故意英文本 {len(INTENTIONALLY_ENGLISH)} 个：{sorted(INTENTIONALLY_ENGLISH)}）"
)
