"""主题暴露度（AN20）：用 PCF 合成主题指数回归，得到「主题纯度」。

价值：判断一只主动基金"到底在赌什么"——比看季报重仓更准，
因为季报滞后 90 天，而 PCF 合成指数是当日权重。

用法：python3 tools/theme_exposure.py --funds 005729,001513
"""
from __future__ import annotations
import argparse, logging, sys
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engine import NnRidgeRegressor  # noqa: E402
from mkt import db  # noqa: E402
from mkt.sources import sse_pcf  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("theme")

THEME_NAME = {
    "512480": "半导体", "515070": "人工智能", "588000": "科创50", "512720": "计算机",
    "515880": "通信", "512330": "信息技术", "516010": "游戏", "512690": "酒",
    "515790": "光伏", "512660": "军工", "512010": "医药", "512880": "证券",
    "512800": "银行", "510300": "沪深300", "510500": "中证500", "512100": "中证1000",
    "510050": "上证50", "511380": "可转债", "512760": "芯片", "515030": "新能源车",
    "513050": "中概互联", "513130": "恒生科技",
}


def build_synthetic(con) -> dict[str, dict[str, float]]:
    """用最新 PCF 权重 × 个股收益，重建各主题指数的历史序列。"""
    td = con.execute("SELECT MAX(trading_day) FROM pcf_weight").fetchone()
    if not td or not td[0]:
        return {}
    td = td[0]

    def ser(sid):
        kl = db.load_klines(con, sid)
        it = sorted(kl.items())
        if len(it) < 30:
            return {}
        return {it[i][0]: (it[i][1] / it[i - 1][1] - 1) * 100 for i in range(1, len(it))}

    out: dict[str, dict[str, float]] = {}
    for (fc,) in con.execute("SELECT DISTINCT fund_code FROM pcf_weight WHERE trading_day=?", (td,)):
        rows = con.execute("SELECT instrument_id, weight FROM pcf_weight WHERE fund_code=? AND trading_day=?",
                           (fc, td)).fetchall()
        parts = {}
        for cid, w in rows:
            r = ser(sse_pcf.to_secid(cid))
            if r:
                parts[cid] = (w, r)
        if len(parts) < 10:
            continue
        dates = None
        for _, r in parts.values():
            s = set(r)
            dates = s if dates is None else dates & s
        if dates and len(dates) > 50:
            out[fc] = {d: sum(w * r[d] for w, r in parts.values()) for d in sorted(dates)}
    return out


class LK:
    def __init__(self, d): self._d = d
    def get(self, sec, dt): return self._d.get(sec, {}).get(dt)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--funds", default="005729,006281,005962,001513,519674,002190,003095")
    ap.add_argument("--top", type=int, default=4)
    args = ap.parse_args()

    con = db.connect()
    syn = build_synthetic(con)
    log.info("可用 PCF 主题指数：%d 个（%s）", len(syn),
             "、".join(THEME_NAME.get(c, c) for c in list(syn)[:8]))
    if not syn:
        return
    book = LK(syn)
    log.info("")
    log.info("%-8s %-22s %-26s %s", "基金", "名称", "主暴露（权重）", "说明")
    log.info("-" * 96)
    for code in [c.strip() for c in args.funds.split(",") if c.strip()]:
        nav = db.load_nav(con, code)
        if not nav:
            continue
        name = con.execute("SELECT name FROM fund_meta WHERE code=?", (code,)).fetchone()
        nm = (name[0] if name else "")[:20]
        d = sorted(nav)[-1]
        cands = [c for c in syn if syn.get(c, {}).get(d) is not None]
        if len(cands) < 4:
            continue
        reg = NnRidgeRegressor(window=60)
        r = reg.fit(d, nav, cands, {}, book, lam=0.05, top_k=None)
        if not r:
            continue
        pairs = sorted(zip(r[0], np.array(r[1]) / 100), key=lambda x: -x[1])[: args.top]
        top_txt = "  ".join(f"{THEME_NAME.get(k,k)} {v*100:.0f}%" for k, v in pairs if v > 0.02)
        total = sum(v for _, v in pairs) * 100
        pure = "高纯度" if total > 80 else ("中等" if total > 55 else "分散")
        log.info("%-8s %-22s %-26s %s（主暴露合计 %.0f%%）", code, nm, top_txt, pure, total)


if __name__ == "__main__":
    main()
