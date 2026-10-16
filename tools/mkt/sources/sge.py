"""上海黄金交易所官方行情（黄金估值的基准源）。"""
from __future__ import annotations
import json
import logging
import re
from datetime import date, datetime

from mkt.http import HTTP

log = logging.getLogger("mkt.sge")


def daily(instid: str = "Au99.99") -> dict[date, float]:
    """日线收盘价（实测可回溯至 2016-12-19，10 年）。字段顺序 [日期,开,收,低,高]（需交叉校验）。"""
    txt = HTTP.get("https://www.sge.com.cn/graph/Dailyhq",
                   params={"instid": instid}, referer="https://www.sge.com.cn/sjzx/mrhq",
                   ttl=12 * 3600)
    data = json.loads(txt or "{}")
    out: dict[date, float] = {}
    for row in data.get("time") or []:
        try:
            out[datetime.strptime(row[0], "%Y-%m-%d").date()] = float(row[2])
        except (ValueError, IndexError, TypeError):
            continue
    return out


def quotation(instid: str = "Au99.99") -> dict:
    """当日分时（times/data/min/max/delaystr）。"""
    txt = HTTP.get("https://www.sge.com.cn/graph/quotations", data={"instid": instid},
                   referer="https://www.sge.com.cn/sjzx/mrhq", ttl=30)
    d = json.loads(txt or "{}")
    return {"times": d.get("times") or [], "data": d.get("data") or [],
            "min": d.get("min"), "max": d.get("max"), "heyue": d.get("heyue"),
            "delay": d.get("delaystr")}


def benchmark_price(start: str, end: str, metal: str = "Au") -> dict[date, dict]:
    """上海金/上海银基准价（SHAU/SHAG）定价轮次数据。

    银行积存金定价基准 —— 公开可得，无需银行接口。
    URL: /sjzx/shanghai{Au|Ag}Auto?start_date=&end_date=
    返回 {date: {morning_last, afternoon_last, rounds, last_price}}
    """
    import re as _re
    from bs4 import BeautifulSoup

    path = "shanghaiAuAuto" if metal == "Au" else "shanghaiAgAuto"
    txt = HTTP.get(f"https://www.sge.com.cn/sjzx/{path}",
                   params={"start_date": start, "end_date": end},
                   referer="https://www.sge.com.cn/sjzx/pmjj", ttl=12 * 3600)
    soup = BeautifulSoup(txt, "lxml")
    rows: dict[date, list[tuple[str, int, float]]] = {}
    for tr in soup.select("tr"):
        tds = [td.get_text(strip=True) for td in tr.find_all("td")]
        if len(tds) < 6 or not _re.match(r"^\d{4}-\d{2}-\d{2}$", tds[1]):
            continue
        try:
            d = datetime.strptime(tds[1], "%Y-%m-%d").date()
            rows.setdefault(d, []).append((tds[3], int(tds[4]), float(tds[5])))
        except (ValueError, IndexError, TypeError):
            continue
    out: dict[date, dict] = {}
    for d, items in rows.items():
        items.sort(key=lambda x: (x[0], x[1]))
        morning = [p for s, _, p in items if "早" in s]
        afternoon = [p for s, _, p in items if "午" in s and "早" not in s]
        out[d] = {
            "morning_last": morning[-1] if morning else None,
            "afternoon_last": afternoon[-1] if afternoon else None,
            "last_price": items[-1][2] if items else None,
            "rounds": len(items),
        }
    return out
