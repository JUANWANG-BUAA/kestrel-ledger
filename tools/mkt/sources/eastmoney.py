"""东方财富 / 天天基金 数据源（已实测可用，详见 docs/02 §A.1、§A.2）。"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from bs4 import BeautifulSoup

from mkt.http import HTTP

log = logging.getLogger("mkt.em")


# --------------------------------------------------------------------------- #
# 数据模型
# --------------------------------------------------------------------------- #
@dataclass(slots=True)
class NavPoint:
    nav_date: date
    unit_nav: float
    acc_nav: float
    pct: float          # 日涨跌幅 %


@dataclass(slots=True)
class HoldingRow:
    report_period: str      # 2025Q4
    secid: str              # 116.00700 / 1.600519
    code: str
    name: str
    weight: float           # 占净值比例 %


@dataclass(slots=True)
class AllocRow:
    report_period: str
    stock_ratio: float | None
    bond_ratio: float | None
    cash_ratio: float | None


# --------------------------------------------------------------------------- #
# 1. 历史净值（f10/lsjz）
# --------------------------------------------------------------------------- #
MOBILE_COMMON = {
    "deviceid": "WapDeviceId",
    "plat": "Android",
    "product": "EFund",
    "version": "6.2.8",
}


def _parse_nav_item(it: dict) -> NavPoint | None:
    try:
        pct_raw = (it.get("JZZZL") or "").strip()
        return NavPoint(
            nav_date=datetime.strptime(it["FSRQ"], "%Y-%m-%d").date(),
            unit_nav=float(it["DWJZ"]),
            acc_nav=float(it.get("LJJZ") or 0) or 0.0,
            pct=float(pct_raw) if pct_raw not in ("", "--", "null") else 0.0,
        )
    except (KeyError, ValueError, TypeError):
        return None


def fund_nav_history(code: str, page_size: int = 1000, max_pages: int = 40) -> list[NavPoint]:
    """全量历史净值。

    主路径：移动端 FundMNHisNetList（实测支持 pageSize=1000，全量 4000+ 条仅 5 次请求）。
    备路径：f10/lsjz（实测 pageSize 被强制为 20，仅作降级）。
    """
    out: list[NavPoint] = []
    for page in range(1, max_pages + 1):
        try:
            txt = HTTP.get(
                "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNHisNetList",
                params={"FCODE": code, "pageIndex": page, "pageSize": page_size, **MOBILE_COMMON},
                ttl=6 * 3600,
            )
            data = json.loads(txt)
            lst = (data or {}).get("Datas") or []
        except Exception as e:  # noqa: BLE001
            log.warning("[%s] 移动端净值第 %s 页失败(%s)，转 f10 备源", code, page, e)
            return _fund_nav_history_f10(code)
        if not lst:
            break
        for it in lst:
            p = _parse_nav_item(it)
            if p:
                out.append(p)
        if len(lst) < page_size:
            break
    if not out:
        return _fund_nav_history_f10(code)
    out.sort(key=lambda x: x.nav_date)
    return out


def _fund_nav_history_f10(code: str, max_pages: int = 260) -> list[NavPoint]:
    """降级路径：f10/lsjz，pageSize 固定 20。"""
    out: list[NavPoint] = []
    for page in range(1, max_pages + 1):
        try:
            txt = HTTP.get(
                "https://api.fund.eastmoney.com/f10/lsjz",
                params={"fundCode": code, "pageIndex": page, "pageSize": 20},
                referer="https://fundf10.eastmoney.com/",
                ttl=6 * 3600,
            )
            lst = ((json.loads(txt) or {}).get("Data") or {}).get("LSJZList") or []
        except Exception:  # noqa: BLE001
            break
        if not lst:
            break
        for it in lst:
            p = _parse_nav_item(it)
            if p:
                out.append(p)
        if len(lst) < 20:
            break
    out.sort(key=lambda x: x.nav_date)
    return out


# --------------------------------------------------------------------------- #
# 2. 基金画像（pingzhongdata，JS 文本）
# --------------------------------------------------------------------------- #
_WHITELIST = (
    "fS_name",
    "fS_code",
    "fund_Rate",
    "fund_sourceRate",
    "syl_1n",
    "syl_6y",
    "syl_3y",
    "syl_1y",
    "Data_assetAllocation",
    "Data_netWorthTrend",
    "Data_currentFundManager",
    "Data_fundSharesPositions",
)


def _extract_var(js: str, name: str) -> str | None:
    """提取 `var NAME = ...` 的值。

    用**括号配对**截断，而非找下一个 `var `——因为 pingzhongdata 里
    变量值后面常跟 JS 注释（如 `};/*业绩评价 [...]*/`），按分号截断会解析失败。
    """
    m = re.search(rf"var\s+{re.escape(name)}\s*=\s*", js)
    if not m:
        return None
    s = m.end()
    if s >= len(js):
        return None
    ch = js[s]

    # 字符串字面量：扫描到配对的引号（处理转义）
    if ch in "\"'":
        quote, i = ch, s + 1
        while i < len(js):
            if js[i] == "\\":
                i += 2
                continue
            if js[i] == quote:
                return js[s : i + 1]
            i += 1
        return None

    # 对象/数组：括号配对扫描（跳过字符串内部）
    if ch in "{[":
        pairs = {"{": "}", "[": "]"}
        depth = 0
        in_str = False
        esc = False
        for i in range(s, len(js)):
            c = js[i]
            if in_str:
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    in_str = False
                continue
            if c == '"':
                in_str = True
            elif c in pairs:
                depth += 1
            elif c in pairs.values():
                depth -= 1
                if depth == 0:
                    return js[s : i + 1]
        return None

    # 数字 / 布尔 / null
    end = js.find(";", s)
    return js[s : end if end != -1 else len(js)].strip()


def _as_json(seg: str | None):
    if not seg:
        return None
    try:
        return json.loads(seg)
    except json.JSONDecodeError:
        return None


@dataclass(slots=True)
class FundProfile:
    code: str
    name: str
    stock_ratio_by_period: dict[str, float]   # 2025Q4 -> 股票占比 %
    nav_dates: list[date]


def fund_profile(code: str) -> FundProfile:
    url = f"https://fund.eastmoney.com/pingzhongdata/{code}.js"
    js = HTTP.get(url, referer="https://fund.eastmoney.com/", ttl=6 * 3600)
    name = (_extract_var(js, "fS_name") or "").strip('"')

    # Data_assetAllocation: categories 为 'YYYY-MM-DD'（季末），series 含股票/债券/现金占比
    alloc: dict[str, float] = {}
    raw_alloc = _as_json(_extract_var(js, "Data_assetAllocation"))
    if isinstance(raw_alloc, dict):
        cats = raw_alloc.get("categories") or []
        for s in raw_alloc.get("series") or []:
            if s.get("name") != "股票占净比":
                continue
            for cat, val in zip(cats, s.get("data") or []):
                if val is None:
                    continue
                try:
                    d = date.fromisoformat(str(cat))
                except ValueError:
                    continue
                alloc[f"{d.year}Q{(d.month - 1) // 3 + 1}"] = float(val)

    nav_dates: list[date] = []
    raw_nav = _as_json(_extract_var(js, "Data_netWorthTrend"))
    if isinstance(raw_nav, list):
        for p in raw_nav:
            try:
                nav_dates.append(datetime.fromtimestamp(p["x"] / 1000).date())
            except Exception:  # noqa: BLE001
                continue

    return FundProfile(code=code, name=name, stock_ratio_by_period=alloc, nav_dates=nav_dates)


# --------------------------------------------------------------------------- #
# 3. 十大重仓（FundArchivesDatas，HTML 片段）
# --------------------------------------------------------------------------- #
def fund_holdings(code: str, year: int, topline: int = 10) -> list[HoldingRow]:
    """返回该年度全部季度的持仓。

    ⚠️ **topline 决定披露口径**（实测）：
        季报只披露前十大 → topline=10 得到 10 只
        **半年报/年报披露全量 → topline=100 得到 49~54 只，合计权重 93~94%**
    → 采集时应**用 topline=100**，让季报仍返回 10 只、年报返回全量（服务端按报告期自动裁剪）。

    secid 直接从 `<a href='//quote.eastmoney.com/unify/r/116.00700'>` 提取，
    因此无需猜测市场前缀（A股/港股/美股/日股/韩股一致处理）。
    """
    url = "https://fundf10.eastmoney.com/FundArchivesDatas.aspx"
    txt = HTTP.get(
        url,
        params={"type": "jjcc", "code": code, "topline": topline, "year": year},
        referer=f"https://fundf10.eastmoney.com/ccmx_{code}.html",
        ttl=7 * 24 * 3600,
    )
    m = re.search(r"content:\"(.*?)\"\s*,\s*arryear", txt, re.S)
    if not m:
        return []
    html = m.group(1).replace('\\"', '"').replace("\\/", "/")
    soup = BeautifulSoup(html, "lxml")

    rows: list[HoldingRow] = []
    for box in soup.select("div.boxitem"):
        head = box.select_one("h4.t")
        if not head:
            continue
        pm = re.search(r"(\d{4})年(\d)季度", head.get_text(" ", strip=True))
        if not pm:
            continue
        period = f"{pm.group(1)}Q{pm.group(2)}"
        table = box.select_one("table")
        if not table:
            continue
        for tr in table.select("tbody tr"):
            tds = tr.find_all("td")
            if len(tds) < 5:
                continue
            a = tds[1].find("a")
            href = (a.get("href") if a else "") or ""
            sm = re.search(r"/unify/r/([0-9]+\.[A-Za-z0-9]+)", href)
            if not sm:
                continue
            secid = sm.group(1)
            code_txt = a.get_text(strip=True)
            name_txt = tds[2].get_text(strip=True)
            wm = re.search(r"([\d.]+)\s*%", tds[4].get_text(strip=True))
            if not wm:
                continue
            rows.append(
                HoldingRow(
                    report_period=period,
                    secid=secid,
                    code=code_txt,
                    name=name_txt,
                    weight=float(wm.group(1)),
                )
            )
    return rows


# --------------------------------------------------------------------------- #
# 4. 行情 / K 线
# --------------------------------------------------------------------------- #
def _em_secids(secids: list[str]) -> str:
    return ",".join(secids)


def quote_batch(secids: list[str], fields: str = "f2,f3,f4,f12,f13,f14,f124") -> dict[str, dict]:
    """批量行情。主源 push2，失败自动切换 push2delay（docs/02 §A.0 降级矩阵）。"""
    out: dict[str, dict] = {}
    for host in ("push2.eastmoney.com", "push2delay.eastmoney.com"):
        try:
            txt = HTTP.get(
                f"https://{host}/api/qt/ulist.np/get",
                params={
                    "fltt": 2,
                    "ut": "fa5fd1943c7b386f172d6893dbfba10b",
                    "fields": fields,
                    "secids": _em_secids(secids),
                },
                referer="https://quote.eastmoney.com/",
                ttl=5,
            )
            data = json.loads(txt)
            diff = ((data or {}).get("data") or {}).get("diff") or []
            for d in diff:
                key = f"{d.get('f13')}.{d.get('f12')}"
                out[key] = d
            if out:
                return out
        except Exception as e:  # noqa: BLE001
            log.debug("quote via %s 失败: %s", host, e)
    return out


def kline_daily(secid: str, adjust: int = 1, limit: int = 10000) -> dict[date, float]:
    """日线收盘价（用于回测中计算持仓股票当日收益）。"""
    txt = HTTP.get(
        "https://push2his.eastmoney.com/api/qt/stock/kline/get",
        params={
            "secid": secid,
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
            "klt": 101,
            "fqt": adjust,
            "end": "20500101",
            "lmt": limit,
        },
        referer="https://quote.eastmoney.com/",
        ttl=12 * 3600,
    )
    data = json.loads(txt)
    klines = ((data or {}).get("data") or {}).get("klines") or []
    out: dict[date, float] = {}
    for line in klines:
        parts = line.split(",")
        if len(parts) < 3:
            continue
        try:
            out[datetime.strptime(parts[0], "%Y-%m-%d").date()] = float(parts[2])  # f53 = 收盘
        except ValueError:
            continue
    return out


def search(key: str, count: int = 10) -> list[dict]:
    txt = HTTP.get(
        "https://searchapi.eastmoney.com/api/suggest/get",
        params={"input": key, "type": 14, "token": "D43BF722C8E33BDC906FB84D85E326E8", "count": count},
        referer="https://www.eastmoney.com/",
        ttl=24 * 3600,
    )
    data = json.loads(txt)
    return ((data or {}).get("QuotationCodeTable") or {}).get("Data") or []


def fund_basic(code: str) -> dict:
    txt = HTTP.get(
        "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNNBasicInformation",
        params={"FCODE": code, **MOBILE_COMMON},
        ttl=24 * 3600,
    )
    return (json.loads(txt) or {}).get("Datas") or {}


# --------------------------------------------------------------------------- #
# 5. 交易日 / 报告期工具
# --------------------------------------------------------------------------- #
def quarter_end(period: str) -> date:
    y, q = int(period[:4]), int(period[-1])
    return date(y, 3 * q, 31) if q in (1, 4) else date(y, 3 * q, 30)


def disclose_date(period: str, lag_days: int = 30) -> date:
    """保守推算披露日（防未来函数）。

    监管要求季度报告在季末后 15 个工作日内披露；此处用日历日 + 30 天缓冲，
    覆盖春节等长假。回测中可用 lag 参数做敏感性分析（0 / 30 / 60）。
    """
    return quarter_end(period) + timedelta(days=lag_days)


# --------------------------------------------------------------------------- #
# 6. 股票分红送配（复权修正的数据源）
# --------------------------------------------------------------------------- #
def stock_dividends(code: str, page_size: int = 60) -> list[dict]:
    """个股分红送配明细。

    用于修正**不复权价在除权日的假跌幅**：
        真实收益 = (P_t + cash_per_share) / P_{t-1} − 1

    字段：
      EX_DIVIDEND_DATE     除权除息日
      PRETAX_BONUS_RMB     税前每 10 股派息（元）
      BONUS_RATIO          送股比例（每 10 股送 X 股）
      IT_RATIO             转增比例（每 10 股转增 X 股）
    """
    txt = HTTP.get(
        "https://datacenter-web.eastmoney.com/api/data/v1/get",
        params={
            "reportName": "RPT_SHAREBONUS_DET",
            "columns": "ALL",
            "filter": f'(SECURITY_CODE="{code}")',
            "pageSize": page_size,
            "sortColumns": "EX_DIVIDEND_DATE",
            "sortTypes": -1,
        },
        referer="https://data.eastmoney.com/",
        ttl=7 * 24 * 3600,
    )
    data = json.loads(txt or "{}")
    rows = ((data.get("result") or {}).get("data")) or []
    out = []
    for r in rows:
        ex = r.get("EX_DIVIDEND_DATE")
        if not ex:
            continue
        try:
            out.append({
                "ex_date": datetime.strptime(ex[:10], "%Y-%m-%d").date(),
                "cash_per_share": float(r.get("PRETAX_BONUS_RMB") or 0) / 10.0,
                "bonus_ratio": float(r.get("BONUS_RATIO") or 0) / 10.0,
                "it_ratio": float(r.get("IT_RATIO") or 0) / 10.0,
                "profile": r.get("IMPL_PLAN_PROFILE") or "",
            })
        except (ValueError, TypeError):
            continue
    return sorted(out, key=lambda x: x["ex_date"])


# --------------------------------------------------------------------------- #
# 7. 基金分红送配（场内 ETF/LOF 复权修正的数据源）
# --------------------------------------------------------------------------- #
def fund_dividends(code: str) -> list[dict]:
    """基金分红送配（含场内 ETF/LOF）。

    ⚠️ 债券 ETF 多按**月/季**分红，实测除权跳空达 −1.1%（z=−11）：
       511010 于 2025-09-23 每 10 份派 14.50 元 → 跳空 1.036%（与实测吻合）
    这些假跳空会污染债券估值因子与信用利差代理。

    数据源：https://fundf10.eastmoney.com/fhsp_{code}.html
    """
    txt = HTTP.get(f"https://fundf10.eastmoney.com/fhsp_{code}.html",
                   referer="https://fundf10.eastmoney.com/", ttl=7 * 24 * 3600)
    soup = BeautifulSoup(txt, "lxml")
    out: list[dict] = []
    for tr in soup.select("table tr"):
        tds = [td.get_text(strip=True) for td in tr.find_all("td")]
        if len(tds) < 4:
            continue
        m = re.match(r"^\d{4}-\d{2}-\d{2}$", tds[2] or "")
        if not m:
            continue
        cash_m = re.search(r"每10份派现金([\d.]+)元", tds[3])
        split_m = re.search(r"每10份(?:送|拆)([\d.]+)", tds[3])
        try:
            out.append({
                "ex_date": datetime.strptime(tds[2], "%Y-%m-%d").date(),
                "record_date": tds[1] if re.match(r"^\d{4}-\d{2}-\d{2}$", tds[1] or "") else None,
                "cash_per_share": float(cash_m.group(1)) / 10.0 if cash_m else 0.0,
                "split_ratio": float(split_m.group(1)) / 10.0 if split_m else 0.0,
                "profile": tds[3],
            })
        except (ValueError, TypeError):
            continue
    return sorted(out, key=lambda x: x["ex_date"])
