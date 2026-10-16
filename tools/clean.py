"""数据清洗：检测并修复行情序列中的三类问题。

① 小数点错位（×10 / ÷10）
     实测：511030 公司债ETF 在 2025-03-19 从 10.4950 跳到 105.0010（正好 ×10）
     债券 ETF 面值约 100 元 → 之前的数据少了一位小数

② 除权跳空（分红/拆分未修正）
     实测：511010 于 2025-09-23 除息，每 10 份派 14.50 元 → −1.113% 假跌幅
     修复：给场内 ETF/LOF 也采分红（`fhsp_{code}.html`）

③ 孤立异常值（数据源错误）
     规则：|日收益| > 阈值（ETF/基金 20%，股票 25%）且无分红解释 → 标记可疑

用法：
    python3 tools/clean.py --scan          # 只扫描报告
    python3 tools/clean.py --fix-scale     # 修复小数点错位
    python3 tools/clean.py --collect-div   # 采集基金分红（供复权）
    python3 tools/clean.py --verify        # 修复后复验
"""
from __future__ import annotations
import argparse
import logging
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from adjust import Div, ensure as adj_ensure, load_dividends  # noqa: E402
from mkt import db  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("clean")

# 场内基金/ETF 标的（代码以 5/15/16 开头）
def is_fund_secid(sid: str) -> bool:
    code = sid.split(".")[-1]
    return code.startswith(("5", "15", "16", "18"))


def scan(con, limit_threshold: float = 0.20) -> list[tuple]:
    """扫描所有标的，返回可疑清单。"""
    out = []
    secids = [r[0] for r in con.execute("SELECT DISTINCT secid FROM stock_daily")]
    for sid in secids:
        kl = db.load_klines(con, sid)
        it = sorted(kl.items())
        if len(it) < 60:
            continue
        divs = load_dividends(con, sid)
        code = sid.split(".")[-1]
        if is_fund_secid(sid):
            thr = limit_threshold                        # ETF/LOF 20%
        elif code.startswith(("30", "68", "8", "4")):
            thr = 0.21                                   # 创业板/科创板/北交所 ±20%
        else:
            thr = 0.11                                   # 主板 ±10%
        for i in range(1, len(it)):
            p0, p1 = it[i - 1][1], it[i][1]
            if p0 <= 0:
                continue
            r = p1 / p0 - 1
            if abs(r) <= thr:
                continue
            # 检查是否可用分红解释
            d = divs.get(it[i][0])
            if d:
                eff = (p1 + d.cash) / p0 - 1
                if abs(eff) <= thr:
                    continue
            ratio = p1 / p0
            if 8 < ratio < 12:
                kind = "小数点错位×10"
            elif 0.08 < ratio < 0.12:
                kind = "小数点错位÷10"
            elif 0.45 < ratio < 0.55:
                kind = "份额折算 1:2（拆分）"
            elif 1.9 < ratio < 2.1:
                kind = "份额折算 2:1（合并）"
            elif 0.28 < ratio < 0.36:
                kind = "份额折算 1:3"
            elif 2.9 < ratio < 3.4:
                kind = "份额折算 3:1"
            else:
                kind = "异常值"
            out.append((sid, it[i][0], p0, p1, r * 100, kind))
    return out


def _anchor_price(con, sid: str) -> float | None:
    """取**官方净值**作为量级锚点（比猜测哪段对可靠得多）。

    ⚠️ 教训：早期版本用"中位数比较"猜测哪段错，把 511030 的
    正确段（105）改成了 10.5，结果整个序列都错了。
    正确做法：以基金官方单位净值为锚（511030 真实净值 108.69）。
    """
    from mkt.sources import eastmoney as em
    if not is_fund_secid(sid):
        return None
    code = sid.split(".")[-1]
    try:
        b = em.fund_basic(code)
        nav = float(b.get("DWJZ") or 0)
        return nav if nav > 0 else None
    except Exception:  # noqa: BLE001
        return None


def fix_scale(con, items: list[tuple]) -> int:
    """修复小数点错位：以官方净值为锚，对**整段**统一缩放。

    判定：median(价格) / 净值
        ≈ 1    → 正确
        ≈ 10   → 需 ÷10
        ≈ 0.1  → 需 ×10
    """
    n = 0
    # 按标的聚合（同一标的可能有多处错位）
    by_sid: dict[str, list[tuple]] = {}
    for it in items:
        if "小数点错位" in it[5]:
            by_sid.setdefault(it[0], []).append(it)

    for sid, evts in by_sid.items():
        kl = db.load_klines(con, sid)
        it = sorted(kl.items())
        if len(it) < 30:
            continue
        anchor = _anchor_price(con, sid)
        if anchor is None:
            log.warning("  %s 无官方净值锚点，跳过", sid)
            continue
        med = float(np.median([v for _, v in it]))
        ratio = med / anchor
        if 0.5 < ratio < 2.0:
            log.info("  %s 量级正常（median/NAV=%.2f），无需缩放", sid, ratio)
            continue
        mult = 1.0
        for cand in (0.1, 0.01, 10.0, 100.0):
            if 0.5 < med * cand / anchor < 2.0:
                mult = cand
                break
        if mult == 1.0:
            log.warning("  %s 无法确定缩放倍数（median/NAV=%.3f），跳过", sid, ratio)
            continue
        con.executemany("INSERT OR REPLACE INTO stock_daily(secid,trade_date,close) VALUES(?,?,?)",
                        [(sid, dd, v * mult) for dd, v in it])
        con.commit()
        con.execute("INSERT INTO data_revision(table_name,row_key,field,old_value,new_value,"
                    "reason,revised_at) VALUES(?,?,?,?,?,?,datetime('now'))",
                    ("stock_daily", sid, "close", f"median={med:.4f}",
                     f"median={med*mult:.4f}",
                     f"小数点错位修正 ×{mult}（锚=官方净值 {anchor}，{len(it)} 行）"))
        con.commit()
        log.info("  %s: ×%.2f 修正 %d 行（median %.3f → %.3f，官方净值 %.3f）",
                 sid, mult, len(it), med, med * mult, anchor)
        n += 1
    return n


def collect_fund_dividends(con) -> int:
    """给场内 ETF/LOF 采集基金分红（复权修正用）。"""
    from mkt.sources import eastmoney as em
    adj_ensure(con)
    secids = [r[0] for r in con.execute(
        "SELECT DISTINCT secid FROM stock_daily WHERE secid LIKE '1.5%' OR secid LIKE '0.15%'"
        " OR secid LIKE '0.16%' OR secid LIKE '1.51%' OR secid LIKE '1.58%'")]
    have = {r[0] for r in con.execute("SELECT DISTINCT secid FROM stock_dividend")}
    todo = [s for s in secids if s not in have]
    log.info("场内基金 %d 只，待采分红 %d 只", len(secids), len(todo))
    ok = 0
    for sid in todo:
        code = sid.split(".")[-1]
        try:
            rows = em.fund_dividends(code)
        except Exception:  # noqa: BLE001
            continue
        if rows:
            con.executemany(
                "INSERT OR REPLACE INTO stock_dividend VALUES(?,?,?,?,?,?)",
                [(sid, r["ex_date"].isoformat(), r["cash_per_share"],
                  r.get("split_ratio", 0.0), 0.0, f"[基金] {r['profile']}") for r in rows])
            con.commit()
            ok += 1
    log.info("基金分红采集：%d 只命中", ok)
    return ok


def fix_split(con, items: list[tuple]) -> int:
    """份额折算修正：写入 stock_dividend（split_ratio 语义）供复权使用。

    真实收益 = (P_t × 拆分倍数) / P_{t-1} − 1
      · 1:2 拆分（价格减半）→ 倍数 2
      · 2:1 合并（价格翻倍）→ 倍数 0.5
    """
    n = 0
    for sid, d, p0, p1, _, kind in items:
        if "份额折算" not in kind:
            continue
        if "1:2" in kind:
            mult = 2.0
        elif "2:1" in kind:
            mult = 0.5
        elif "1:3" in kind:
            mult = 3.0
        elif "3:1" in kind:
            mult = 1.0 / 3.0
        else:
            continue
        con.execute(
            "INSERT OR REPLACE INTO stock_dividend(secid,ex_date,cash_per_share,"
            "bonus_ratio,it_ratio,profile) VALUES(?,?,0,0,?,?) "
            "ON CONFLICT(secid,ex_date) DO UPDATE SET it_ratio=excluded.it_ratio,"
            " profile=excluded.profile",
            (sid, d, mult - 1.0, f"[自动检测] {kind}"))
        con.execute("INSERT INTO data_revision(table_name,row_key,field,old_value,new_value,"
                    "reason,revised_at) VALUES(?,?,?,?,?,?,datetime('now'))",
                    ("stock_daily", f"{sid}:{d}", "split", f"{p0}→{p1}",
                     f"x{mult}", kind))
        n += 1
    con.commit()
    if n:
        log.info("  份额折算修正 %d 处", n)
    return n


def verify(con, threshold: float = 0.20) -> None:
    items = scan(con, threshold)
    by_kind: dict[str, int] = {}
    for it in items:
        by_kind[it[5]] = by_kind.get(it[5], 0) + 1
    log.info("=" * 70)
    log.info("清洗后复验：可疑点 %d 个", len(items))
    for k, v in sorted(by_kind.items(), key=lambda x: -x[1]):
        log.info("  %-16s %4d", k, v)
    fund_issues = [x for x in items if is_fund_secid(x[0])]
    log.info("  其中场内基金/ETF: %d 个", len(fund_issues))
    for sid, d, p0, p1, r, kind in fund_issues[:8]:
        log.info("    %-12s %s  %8.4f → %8.4f  %+7.1f%%  %s", sid, d, p0, p1, r, kind)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scan", action="store_true")
    ap.add_argument("--fix-scale", action="store_true")
    ap.add_argument("--collect-div", action="store_true")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--all", action="store_true")
    args = ap.parse_args()
    do_all = args.all or not any([args.scan, args.fix_scale, args.collect_div, args.verify])
    con = db.connect()
    con.executescript(
        "CREATE TABLE IF NOT EXISTS data_revision("
        "id INTEGER PRIMARY KEY AUTOINCREMENT, table_name TEXT, row_key TEXT, field TEXT,"
        "old_value TEXT, new_value TEXT, reason TEXT, revised_at TEXT);")
    con.commit()
    if args.collect_div or do_all:
        log.info("▶ 采集基金分红（场内 ETF/LOF）")
        collect_fund_dividends(con)
    if args.scan or do_all:
        log.info("▶ 扫描异常")
        items = scan(con)
        by_kind: dict[str, int] = {}
        for it in items:
            by_kind[it[5]] = by_kind.get(it[5], 0) + 1
        for k, v in sorted(by_kind.items(), key=lambda x: -x[1]):
            log.info("  %-16s %4d", k, v)
    if args.fix_scale or do_all:
        log.info("▶ 修复小数点错位")
        items = scan(con)
        log.info("  修正 %d 个标的", fix_scale(con, items))
        log.info("▶ 修复份额折算")
        fix_split(con, scan(con))
    if args.verify or do_all:
        verify(con)
    con.close()


if __name__ == "__main__":
    main()
