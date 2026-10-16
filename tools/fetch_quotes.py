"""并发批量采集行情（可断点续跑）。

用途：PCF 成分股 / 因子池的回填。
    python3 tools/fetch_quotes.py --pcf-components          # 采 PCF 全部成分股
    python3 tools/fetch_quotes.py --secids 1.600519,0.000001
    python3 tools/fetch_quotes.py --pcf-components -j 8
"""
from __future__ import annotations
import argparse, logging, sys, time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mkt import db, kline  # noqa: E402
from mkt.sources import sse_pcf  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("fetch_quotes")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--secids", default="")
    ap.add_argument("--pcf-components", action="store_true")
    ap.add_argument("-j", "--jobs", type=int, default=6)
    ap.add_argument("--db", default=str(db.DB_PATH))
    args = ap.parse_args()

    con = db.connect(Path(args.db))
    targets: set[str] = set()
    if args.secids:
        targets |= {s.strip() for s in args.secids.split(",") if s.strip()}
    if args.pcf_components:
        for (cid,) in con.execute("SELECT DISTINCT instrument_id FROM pcf_component"):
            sid = sse_pcf.to_secid(cid)
            if sid:
                targets.add(sid)
    have = {r[0] for r in con.execute(
        "SELECT secid FROM stock_daily GROUP BY secid HAVING COUNT(*)>=100")}
    todo = sorted(targets - have)
    log.info("目标 %d 只，已有 %d 只，待采 %d 只，并发 %d",
             len(targets), len(targets & have), len(todo), args.jobs)
    if not todo:
        log.info("无需采集"); return

    t0 = time.time()
    done = ok = 0
    with ThreadPoolExecutor(max_workers=args.jobs) as ex:
        futs = {ex.submit(kline.fetch, sid): sid for sid in todo}
        for f in as_completed(futs):
            sid = futs[f]
            done += 1
            try:
                d, src = f.result()
            except Exception:  # noqa: BLE001
                d, src = {}, "err"
            if d:
                con2 = db.connect(Path(args.db))
                db.upsert_klines(con2, sid, d)
                con2.close()
                ok += 1
            if done % 100 == 0 or done == len(todo):
                el = time.time() - t0
                log.info("  %d/%d  成功 %d  用时 %.0fs  速率 %.1f 只/秒  熔断=%s",
                         done, len(todo), ok, el, done / max(el, 1e-9), kline.BREAKER.states())
    log.info("完成：成功 %d/%d，用时 %.0fs", ok, len(todo), time.time() - t0)


if __name__ == "__main__":
    main()
