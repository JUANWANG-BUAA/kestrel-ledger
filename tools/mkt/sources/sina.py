"""新浪期货/债券行情（国债期货是债券基金盘中估值的关键数据源）。"""
from __future__ import annotations
import json
import logging
import re
from datetime import date, datetime

from mkt.http import HTTP

log = logging.getLogger("mkt.sina")

CFFEX_BONDS = {"TS0": "2年期国债期货", "TF0": "5年期国债期货",
               "T0": "10年期国债期货", "TL0": "30年期国债期货"}


def futures_daily(symbol: str) -> dict[date, dict]:
    """国债期货日线（新浪，2017 至今）。返回 {date: {o,h,l,c,v}}"""
    url = ("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/"
           f"var%20_{symbol}=/InnerFuturesNewService.getDailyKLine")
    txt = HTTP.get(url, params={"symbol": symbol},
                   referer="https://finance.sina.com.cn/", ttl=12 * 3600)
    m = re.search(r"\((\[.*\])\)", txt, re.S) or re.search(r"=\((\[.*\])\)", txt, re.S)
    raw = m.group(1) if m else None
    if not raw:
        i, j = txt.find("["), txt.rfind("]")
        raw = txt[i:j + 1] if i >= 0 and j > i else None
    if not raw:
        return {}
    try:
        rows = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    out = {}
    for r in rows:
        try:
            out[datetime.strptime(r["d"], "%Y-%m-%d").date()] = {
                "o": float(r["o"]), "h": float(r["h"]), "l": float(r["l"]),
                "c": float(r["c"]), "v": float(r.get("v") or 0),
            }
        except (KeyError, ValueError, TypeError):
            continue
    return out


def bond_futures_quotes() -> dict[str, dict]:
    """实时报价（盘中可用）。"""
    syms = ",".join(f"nf_{s}" for s in CFFEX_BONDS)
    txt = HTTP.get("https://hq.sinajs.cn/list=" + syms,
                   referer="https://finance.sina.com.cn/", ttl=3)
    out = {}
    for line in txt.splitlines():
        m = re.match(r'var hq_str_nf_(\w+)="(.*)";', line)
        if not m:
            continue
        sym, body = m.group(1), m.group(2)
        f = body.split(",")
        if len(f) < 10:
            continue
        try:
            out[sym] = {"name": CFFEX_BONDS.get(sym, sym), "last": float(f[0]),
                        "prev_close": float(f[7]) if f[7] else None,
                        "open": float(f[2]), "high": float(f[3]), "low": float(f[4]),
                        "date": f[17] if len(f) > 17 else "", "time": f[18] if len(f) > 18 else ""}
        except (ValueError, IndexError):
            continue
    return out


def futures_min(symbol: str, minutes: int = 30) -> list[tuple[str, float]]:
    """国债期货分钟线。minutes ∈ {5,15,30}；30 分钟可回溯约 6 个月。"""
    url = ("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/"
           f"var%20t=/InnerFuturesNewService.getFewMinLine")
    txt = HTTP.get(url, params={"symbol": symbol, "type": minutes},
                   referer="https://finance.sina.com.cn/", ttl=6 * 3600)
    i, j = txt.find("[["), txt.rfind("]]")
    if i < 0 or j < 0:
        i, j = txt.find("[{"), txt.rfind("}]")
        if i < 0:
            return []
    seg = txt[i:j + 2]
    try:
        rows = json.loads(seg)
    except json.JSONDecodeError:
        return []
    out = []
    for r in rows:
        try:
            dt = r["d"].replace("-", "").replace(" ", "").replace(":", "")[:12]  # yyyymmddhhmm
            out.append((dt, float(r["c"])))
        except (KeyError, ValueError, TypeError):
            continue
    return out


GLOBAL_FUTURES = {"XAU": "伦敦金", "GC": "纽约金", "XAG": "伦敦银", "CAD": "伦敦铜",
                  "CL": "纽约原油", "HG": "纽约铜"}


def global_futures_daily(symbol: str) -> dict[date, float]:
    """新浪外盘期货日线（XAU 自 2006-10-16，GC 自 2016-10-17）。"""
    url = ("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/"
           f"var%20_hf_{symbol}=/GlobalFuturesService.getGlobalFuturesDailyKLine")
    txt = HTTP.get(url, params={"symbol": symbol},
                   referer="https://finance.sina.com.cn/", ttl=12 * 3600)
    i, j = txt.find("[{"), txt.rfind("}]")
    if i < 0:
        return {}
    try:
        rows = json.loads(txt[i:j + 2])
    except json.JSONDecodeError:
        return {}
    out = {}
    for r in rows:
        try:
            out[datetime.strptime(r["date"], "%Y-%m-%d").date()] = float(r["close"])
        except (KeyError, ValueError, TypeError):
            continue
    return out


def forex_daily(symbol: str = "USDCNY") -> dict[date, float]:
    """新浪外汇日线（USDCNY 自 1994-08-30；USDCNH 自 2014-11-07）。

    返回格式特殊：`"date,o,h,l,c,|date,o,h,l,c,|..."`（竖线分隔）
    """
    url = ("https://vip.stock.finance.sina.com.cn/forex/api/jsonp.php/"
           f"var%20_{symbol}=/NewForexService.getDayKLine")
    txt = HTTP.get(url, params={"symbol": symbol},
                   referer="https://finance.sina.com.cn/", ttl=12 * 3600)
    a, b = txt.find('("'), txt.rfind('")')
    if a < 0 or b < 0:
        return {}
    body = txt[a + 2: b]
    out: dict[date, float] = {}
    for rec in body.split("|"):
        parts = rec.split(",")
        if len(parts) < 5:
            continue
        try:
            out[datetime.strptime(parts[0], "%Y-%m-%d").date()] = float(parts[4])  # close
        except (ValueError, IndexError):
            continue
    return out
