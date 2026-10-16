"""上交所 ETF 申购赎回清单（PCF）解析器。

实测（2026-10-15）：
  GET https://query.sse.com.cn/etfDownload/downloadETF2Bulletin.do?fundCode={6位代码}
  Referer: https://www.sse.com.cn/disclosure/fund/etflist/
  → XML：ssepcf_{code}_{yyyymmdd}.xml

XML 结构：
  <SSEPortfolioCompositionFile>
    <FundInstrumentID>510300</FundInstrumentID>
    <CreationRedemptionUnit>900000</CreationRedemptionUnit>   一篮子份额
    <TradingDay>20261015</TradingDay>                         ★ 当日（开盘前公布）
    <PreTradingDay>20261014</PreTradingDay>
    <NAVperCU>3860129.69</NAVperCU>                           每篮子净值
    <NAV>4.289</NAV>                                          ★ 上一交易日单位净值
    <EstimatedCashComponent>104665.69</EstimatedCashComponent> 预估现金替代
    <ComponentList>
      <Component>
        <InstrumentID>000001</InstrumentID>
        <InstrumentName>平安银行</InstrumentName>
        <Quantity>1600</Quantity>                             ★ 数量（可换算权重）
        <SubstitutionFlag>1</SubstitutionFlag>
        ...
      </Component>

⚠️ **不支持历史查询**：任何日期参数都被忽略，只返回当日 → 历史 PCF 必须每日累积。

⚠️ 仅**上交所**ETF 有此接口；深交所 ETF 需另找（`CATALOGID` 待定位）。
"""
from __future__ import annotations

import logging
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import date

from mkt.http import HTTP

log = logging.getLogger("mkt.pcf")

UA_HEADERS = {"Referer": "https://www.sse.com.cn/disclosure/fund/etflist/"}


@dataclass(slots=True)
class PcfComponent:
    instrument_id: str
    name: str
    quantity: float
    substitution_flag: int
    cash_amount: float


@dataclass(slots=True)
class Pcf:
    fund_code: str
    trading_day: date            # 清单适用的交易日（当日）
    pre_trading_day: date | None  # NAV 对应的日期
    nav: float                   # 上一交易日单位净值
    nav_per_cu: float            # 每篮子净值
    creation_unit: float         # 一篮子份额
    cash_component: float
    components: list[PcfComponent] = field(default_factory=list)

    def weights(self, prices: dict[str, float]) -> dict[str, float]:
        """按给定价格计算各成分股权重（%）。

        prices: {6 位股票代码: 价格}。缺失价格的成分股按 0 计入（并以 cash 项补足）。
        """
        vals: dict[str, float] = {}
        total = 0.0
        for c in self.components:
            p = prices.get(c.instrument_id)
            if p is None or p <= 0:
                continue
            v = c.quantity * p
            vals[c.instrument_id] = v
            total += v
        total += abs(self.cash_component)
        if total <= 0:
            return {}
        return {k: v / total * 100.0 for k, v in vals.items()}


def to_secid(code: str) -> str | None:
    """PCF 成分代码 → 东财 secid。

    A 股：6 位（600519 → 1.600519）
    港股：1~5 位数字（1024 / 00700 → 116.01024 / 116.00700）← 中概互联、恒生科技 ETF 用
    非证券（国债托管代码、AU9999、指数）→ None
    """
    code = (code or "").strip()
    if not code.isdigit():
        return None                                  # Au9999 / 恒生科技 等
    if len(code) == 6:
        if code.startswith(("60", "68", "51", "58", "56", "50", "11")):
            return f"1.{code}"
        if code.startswith(("00", "30", "12", "15", "16", "18", "39")):
            return f"0.{code}"
        if code.startswith("01"):                    # 国债托管代码（019xxx）→ 无行情
            return None
        return None
    if 1 <= len(code) <= 5:                          # 港股
        return f"116.{code.zfill(5)}"
    return None


def fetch(code: str, use_cache: bool = True) -> Pcf | None:
    """下载并解析当日 PCF。"""
    try:
        xml = HTTP.get(
            "https://query.sse.com.cn/etfDownload/downloadETF2Bulletin.do",
            params={"fundCode": code},
            referer=UA_HEADERS["Referer"],
            ttl=1800,
            use_cache=use_cache,
            retries=2,
        )
    except Exception as e:  # noqa: BLE001
        log.debug("PCF %s 下载失败: %s", code, e)
        return None
    if "<SSEPortfolioCompositionFile" not in xml:
        return None
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None

    def _d(tag: str) -> date | None:
        v = root.findtext(tag)
        try:
            return date(int(v[:4]), int(v[4:6]), int(v[6:8])) if v and len(v) == 8 else None
        except (ValueError, TypeError):
            return None

    def _f(tag: str, default: float = 0.0) -> float:
        try:
            return float(root.findtext(tag) or default)
        except (ValueError, TypeError):
            return default

    comps = []
    for c in root.findall("ComponentList/Component"):
        comps.append(
            PcfComponent(
                instrument_id=(c.findtext("InstrumentID") or "").strip(),
                name=(c.findtext("InstrumentName") or "").strip(),
                quantity=_f_from(c, "Quantity"),
                substitution_flag=int(_f_from(c, "SubstitutionFlag")),
                cash_amount=_f_from(c, "SubstitutionCashAmount"),
            )
        )
    return Pcf(
        fund_code=root.findtext("FundInstrumentID") or code,
        trading_day=_d("TradingDay") or date.today(),
        pre_trading_day=_d("PreTradingDay"),
        nav=_f("NAV"),
        nav_per_cu=_f("NAVperCU"),
        creation_unit=_f("CreationRedemptionUnit"),
        cash_component=_f("EstimatedCashComponent"),
        components=comps,
    )


def _f_from(node: ET.Element, tag: str, default: float = 0.0) -> float:
    try:
        return float(node.findtext(tag) or default)
    except (ValueError, TypeError):
        return default
