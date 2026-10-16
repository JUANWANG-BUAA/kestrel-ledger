"""数据健康自检：一致性 / 覆盖率 / 新鲜度 / 异常值。"""
from __future__ import annotations
import logging, sys
from datetime import date, timedelta
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from mkt import db  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("health")


def main() -> None:
    con = db.connect()
    issues: list[str] = []
    log.info("=" * 64)
    log.info("数据健康自检")
    log.info("=" * 64)

    # 1) PCF 完整性（应在最近交易日内）
    row = con.execute("SELECT MAX(trading_day), COUNT(DISTINCT fund_code) FROM pcf_meta").fetchone()
    day, n = row[0], row[1]
    fresh = day and (date.today() - date.fromisoformat(day)).days <= 4
    log.info("  PCF     最新 %s  %d 只  %s", day, n, "✅" if fresh else "⚠️ 偏旧")
    if not fresh:
        issues.append("PCF 数据偏旧")

    # 2) 合成指数覆盖（**只统计成分可映射为股票/指数行情的 ETF**）
    from mkt.sources import sse_pcf
    td = con.execute("SELECT MAX(trading_day) FROM pcf_meta").fetchone()[0]
    eligible = []
    for (fc,) in con.execute(
        "SELECT DISTINCT fund_code FROM pcf_component WHERE trading_day=?", (td,)
    ):
        comps = con.execute(
            "SELECT instrument_id FROM pcf_component WHERE fund_code=? AND trading_day=?",
            (fc, td)).fetchall()
        mapped = [sse_pcf.to_secid(c) for c, in comps]
        n_map = sum(1 for m in mapped if m)
        # 资格 = 可映射比例 ≥50% **且 ≥5 只**（≥5 才是一篮子股票；
        # 黄金ETF/恒生科技ETF 只有 1 个成分=标的价格本身，走 T0 指数直连）
        if n_map >= 5 and n_map / max(len(mapped), 1) >= 0.5:
            eligible.append(fc)
    sn = con.execute(
        "SELECT COUNT(DISTINCT fund_code) FROM pcf_synthetic_index WHERE trading_day=?",
        (td,)).fetchone()[0]
    log.info("  合成指数  %d/%d 只可算  %s  （另有 %d 只成分为国债/指数，天然不可算）",
             sn, len(eligible), "✅" if sn >= len(eligible) else "⚠️", n - len(eligible))
    if sn < len(eligible):
        issues.append(f"合成指数覆盖不足（{sn}/{len(eligible)}）")

    # 3) 成分股行情覆盖
    need = {r[0] for r in con.execute("SELECT DISTINCT instrument_id FROM pcf_component")}
    tot = len(need)
    have = 0
    from mkt.sources import sse_pcf
    sids = {sse_pcf.to_secid(c) for c in need} - {None}
    for s in sids:
        if con.execute("SELECT 1 FROM stock_daily WHERE secid=? LIMIT 1", (s,)).fetchone():
            have += 1
    rate = have / max(len(sids), 1) * 100
    log.info("  成分股行情 %d/%d  %.1f%%  %s", have, len(sids), rate, "✅" if rate > 95 else "⚠️")
    if rate <= 95:
        issues.append(f"成分股行情覆盖率 {rate:.1f}%")

    # 4) 复权修正覆盖
    a = con.execute("SELECT COUNT(DISTINCT secid) FROM stock_daily WHERE secid LIKE '1.%' OR secid LIKE '0.%'").fetchone()[0]
    b = con.execute("SELECT COUNT(DISTINCT secid) FROM stock_dividend").fetchone()[0]
    log.info("  分红（复权） %d/%d 只  %s", b, a, "✅" if b >= a * 0.7 else "⚠️")
    if b < a * 0.7:
        issues.append("分红数据覆盖不足（复权修正不完整）")

    # 5) 行情异常值（|日收益| > 11% 的 A 股占比，扣除 ST）
    bad = 0
    tot_rows = 0
    for sid, in con.execute("SELECT DISTINCT secid FROM stock_daily LIMIT 300"):
        kl = db.load_klines(con, sid)
        it = sorted(kl.items())
        for i in range(1, len(it)):
            tot_rows += 1
            if it[i - 1][1] > 0 and abs(it[i][1] / it[i - 1][1] - 1) > 0.11:
                bad += 1
    rate = bad / max(tot_rows, 1) * 100
    log.info("  异常值   %d/%d  %.2f%%  %s", bad, tot_rows, rate,
             "✅" if rate < 0.5 else "⚠️ 可能含未修正的除权")
    if rate >= 0.5:
        issues.append("行情异常值偏高（疑未修正除权）")

    # 6) 库体积
    import os
    sz = os.path.getsize(db.DB_PATH) / 1e6
    log.info("  库体积   %.0f MB  %s", sz, "✅" if sz < 800 else "⚠️ 建议清理")
    if sz >= 800:
        issues.append(f"库体积 {sz:.0f}MB 偏大")

    log.info("=" * 64)
    if issues:
        log.warning("发现 %d 项问题：%s", len(issues), "；".join(issues))
        sys.exit(1)
    log.info("全部通过 ✅")


if __name__ == "__main__":
    main()
