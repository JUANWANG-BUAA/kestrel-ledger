"""文档一致性检查器：把「文档是否漏了东西」变成可执行的检查。

检查项：
  1. 文档引用的工具脚本是否存在
  2. 实际工具是否都被文档提及
  3. 文档引用的数据表是否与库一致
  4. 关键数字是否有陈旧残留（精度、脚本数、样本数）
  5. 交叉引用（docs/0X §）是否指向存在的章节
  6. 命名规范（禁止与标准库同名的脚本）

用法：python3 tools/doc_check.py
"""
from __future__ import annotations
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = sorted((ROOT / "docs").glob("*.md")) + [ROOT / "README.md", ROOT / "tools" / "README.md"]
STDLIB_FORBIDDEN = {"calendar", "json", "time", "random", "types", "email", "code", "copy",
                    "io", "re", "ast", "token", "test", "queue", "select", "socket", "signal",
                    "statistics", "string", "struct", "tempfile", "warnings", "secrets"}
issues: list[str] = []


def read_all() -> str:
    return "\n".join(p.read_text(encoding="utf-8") for p in DOCS if p.exists())


HISTORICAL_MARKERS = ("已重命名", "旧名", "曾", "抢了", "历史", "曾因", "重命名为",
                      "确认遮蔽", "文件不存在")


def check_referenced_tools(text: str) -> None:
    """检查引用有效性。

    例外：**描述历史事故**时会有意提到已废弃的旧名（如 calendar.py），
    若同一行含历史标记词则跳过。
    """
    lines = text.splitlines()
    for m in re.finditer(r"tools/(?:mkt/)?(?:sources/)?[a-z_0-9]+\.(?:py|sh)", text):
        ref = m.group(0)
        if (ROOT / ref).exists():
            continue
        # 找该引用所在行（粗略定位）
        pos = m.start()
        line = ""
        acc = 0
        for ln in lines:
            acc += len(ln) + 1
            if acc > pos:
                line = ln
                break
        if any(k in line for k in HISTORICAL_MARKERS):
            continue
        issues.append(f"[引用失效] 文档提到 {ref}，但文件不存在")


def check_undocumented_tools(text: str) -> None:
    for f in sorted((ROOT / "tools").glob("*.py")) + sorted((ROOT / "tools").glob("*.sh")):
        if f.name == "doc_check.py":
            continue
        if f.name not in text:
            issues.append(f"[未文档化] {f.relative_to(ROOT)} 未被任何文档提及")


def check_tables(text: str) -> None:
    con = sqlite3.connect(ROOT / "data" / "research.db")
    tabs = [r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
    con.close()
    missing = [t for t in sorted(tabs) if t not in text]
    if missing:
        issues.append(f"[表未文档化] {len(missing)} 张表未被文档提及: {', '.join(missing)}")


def check_stale_numbers(text: str) -> None:
    """关键数字的一致性（以实测为准）。"""
    # 精度数字：若同一段内已注明样本量，则视为已解释
    for m in re.finditer(r"0\.272%", text):
        seg = text[max(0, m.start() - 400): m.start() + 400]
        if any(k in seg for k in ("13 只", "13只", "扩样", "32 只", "32只")):
            continue
        issues.append("[陈旧数字] 0.272% 未注明样本口径（应为 13 只样本，32 只样本为 0.308%）")
    if re.search(r"覆盖\s*13\s*只", text) and "扩样" not in text:
        issues.append("[陈旧数字] 样本数 13 已过时（当前 32）")


def check_stdlib_collision() -> None:
    for f in (ROOT / "tools").glob("*.py"):
        if f.stem in STDLIB_FORBIDDEN:
            issues.append(f"[命名冲突] tools/{f.name} 与标准库同名，会遮蔽标准库")


def check_xrefs(text: str) -> None:
    """交叉引用 docs/0X 是否存在该文件。"""
    for ref in sorted(set(re.findall(r"docs/(0\d-[^`\s)]+\.md)", text))):
        if not (ROOT / "docs" / ref).exists():
            issues.append(f"[交叉引用失效] docs/{ref}")


def check_actual_counts() -> dict:
    con = sqlite3.connect(ROOT / "data" / "research.db")
    rows = sum(con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
               for (t,) in con.execute(
                   "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"))
    con.close()
    return {
        "脚本": len(list((ROOT / "tools").glob("*.py"))) + len(list((ROOT / "tools").glob("*.sh"))),
        "文档": len(list((ROOT / "docs").glob("*.md"))) + 2,
        "表行数": rows,
        "库MB": round((ROOT / "data" / "research.db").stat().st_size / 1e6),
    }


def main() -> None:
    text = read_all()
    check_referenced_tools(text)
    check_undocumented_tools(text)
    check_tables(text)
    check_stale_numbers(text)
    check_stdlib_collision()
    check_xrefs(text)

    print("=" * 72)
    print("文档一致性检查")
    print("=" * 72)
    facts = check_actual_counts()
    for k, v in facts.items():
        print(f"  实际 {k:<6} {v:,}")
    print("-" * 72)
    if not issues:
        print("  ✅ 全部一致")
    else:
        print(f"  发现 {len(issues)} 项：")
        for i in issues:
            print(f"    ⚠️ {i}")
    print("=" * 72)
    sys.exit(1 if issues else 0)


if __name__ == "__main__":
    main()
