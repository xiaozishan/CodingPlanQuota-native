# -*- coding: utf-8 -*-
"""把 Provider.apiKey 改名为 token，并修掉被脱敏写坏的 `***` 占位。"""
import os
import re

ROOT = r"C:\Users\Administrator\.openclaw\workspace\projects\cpa-native\app\src\main\java\io\paimon\quota"

changed = []
for dirpath, _dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        s = open(path, encoding="utf-8").read()
        orig = s

        # 1) 被脱敏写坏的字段声明
        s = re.sub(r"val apiKey\s*:\s*\*\*\*\s*=\s*\"\"", 'val token: String = ""', s)
        # 2) 被脱敏写坏的命名参数 / 调用
        s = re.sub(r"apiKey\s*=\s*\*\*\*\s*\)", "token = keyText)", s)
        s = re.sub(r"apiKey\s*=\s*\*\*\*", "token = keyText", s)
        # 3) 属性改名
        s = s.replace(".apiKey", ".token")
        s = re.sub(r"\bapiKey\s*=\s*", "token = ", s)

        if s != orig:
            open(path, "w", encoding="utf-8", newline="").write(s)
            changed.append(os.path.relpath(path, ROOT))

print("changed:", changed)

# 复查还有没有残留 ***
left = []
for dirpath, _dirs, files in os.walk(ROOT):
    for fn in files:
        if fn.endswith(".kt"):
            p = os.path.join(dirpath, fn)
            for i, line in enumerate(open(p, encoding="utf-8"), 1):
                if "***" in line:
                    left.append(f"{os.path.relpath(p, ROOT)}:{i}: {line.strip()}")
print("remaining ***:", len(left))
for l in left:
    print("  ", l)
