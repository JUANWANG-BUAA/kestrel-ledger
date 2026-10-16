"""接口探针：每日对关键数据源发最小请求，验证可用性与字段完整性。

对应 docs/11 §I.7.2（AU3）。可接入 CI 与定时任务。

用法：
    python3 tools/probe.py            # 全量探针
    python3 tools/probe.py --quick    # 只探核心 8 项
"""
from __future__ import annotations
import argparse, json, logging, sys, time
from datetime import date
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from mkt.http import HTTP  # noqa: E402
from mkt import db  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("probe")


def _json(txt):
    return json.loads(txt)


PROBES = {
    # name: (callable, 期望字段校验)
    "基金净值(移动端)": lambda: (
        _json(HTTP.get("https://fundmobapi.eastmoney.com/FundMNewApi/FundMNHisNetList",
                       params={"FCODE": "110011", "pageIndex": 1, "pageSize": 5,
                               "deviceid": "WapDeviceId", "plat": "Android",
                               "product": "EFund", "version": "6.2.8"}, ttl=300))
        .get("Datas") or [],
        lambda d: len(d) > 0 and "DWJZ" in d[0]),
    "基金画像(pingzhongdata)": lambda: (
        HTTP.get("https://fund.eastmoney.com/pingzhongdata/110011.js",
                 referer="https://fund.eastmoney.com/", ttl=300),
        lambda t: "fS_name" in t and "Data_netWorthTrend" in t),
    "季报重仓(jjcc)": lambda: (
        HTTP.get("https://fundf10.eastmoney.com/FundArchivesDatas.aspx",
                 params={"type": "jjcc", "code": "110011", "topline": 10, "year": date.today().year},
                 referer="https://fundf10.eastmoney.com/", ttl=300),
        lambda t: "content:" in t),
    "持仓+行业+ETFCODE": lambda: (
        _json(HTTP.get("https://fundmobapi.eastmoney.com/FundMNewApi/FundMNInverstPosition",
                       params={"FCODE": "002190", "deviceid": "WapDeviceId", "plat": "Android",
                               "product": "EFund", "version": "6.2.8"}, ttl=300)).get("Datas") or {},
        lambda d: "fundStocks" in d),
    "PCF(上交所)": lambda: (
        HTTP.get("https://query.sse.com.cn/etfDownload/downloadETF2Bulletin.do",
                 params={"fundCode": "510300"},
                 referer="https://www.sse.com.cn/disclosure/fund/etflist/", ttl=300),
        lambda t: "<ComponentList>" in t and "<NAV>" in t),
    "个股日线(腾讯)": lambda: (
        HTTP.get("https://proxy.finance.qq.com/ifzqgtimg/appstock/app/fqkline/get",
                 params={"param": "sh600519,day,,,20,"}, referer="https://gu.qq.com/", ttl=300),
        lambda t: "\"day\"" in t or "\"qfqday\"" in t),
    "个股日线(新浪备源)": lambda: (
        HTTP.get("https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData",
                 params={"symbol": "sh600519", "scale": 240, "ma": "no", "datalen": 5},
                 referer="https://finance.sina.com.cn/", ttl=300),
        lambda t: "close" in t),
    "国债期货(新浪日线)": lambda: (
        HTTP.get("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/"
                 "var%20_T0=/InnerFuturesNewService.getDailyKLine",
                 params={"symbol": "T0"}, referer="https://finance.sina.com.cn/", ttl=300),
        lambda t: "\"c\"" in t),
    "上金所 AU9999": lambda: (
        _json(HTTP.get("https://www.sge.com.cn/graph/Dailyhq", params={"instid": "Au99.99"},
                       referer="https://www.sge.com.cn/sjzx/mrhq", ttl=300)),
        lambda d: bool(d.get("time"))),
    "上金所 SHAU(上海金)": lambda: (
        HTTP.get("https://www.sge.com.cn/sjzx/shanghaiAuAuto",
                 params={"start_date": (date.today().replace(day=1)).isoformat(),
                         "end_date": date.today().isoformat()},
                 referer="https://www.sge.com.cn/sjzx/pmjj", ttl=300),
        lambda t: "SHAU" in t),
    "国际金价 XAU": lambda: (
        HTTP.get("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/"
                 "var%20_hf_XAU=/GlobalFuturesService.getGlobalFuturesDailyKLine",
                 params={"symbol": "XAU"}, referer="https://finance.sina.com.cn/", ttl=300),
        lambda t: "\"close\"" in t),
    "汇率 USDCNY": lambda: (
        HTTP.get("https://vip.stock.finance.sina.com.cn/forex/api/jsonp.php/"
                 "var%20_USDCNY=/NewForexService.getDayKLine",
                 params={"symbol": "USDCNY"}, referer="https://finance.sina.com.cn/", ttl=300),
        lambda t: "1994" in t or "," in t),
    "分红送配": lambda: (
        _json(HTTP.get("https://datacenter-web.eastmoney.com/api/data/v1/get",
                       params={"reportName": "RPT_SHAREBONUS_DET", "columns": "ALL",
                               "filter": '(SECURITY_CODE="600519")', "pageSize": 3,
                               "sortColumns": "EX_DIVIDEND_DATE", "sortTypes": -1},
                       referer="https://data.eastmoney.com/", ttl=300)),
        lambda d: bool((d.get("result") or {}).get("data"))),
    "实时行情批量": lambda: (
        _json(HTTP.get("https://push2.eastmoney.com/api/qt/ulist.np/get",
                       params={"fltt": 2, "ut": "fa5fd1943c7b386f172d6893dbfba10b",
                               "fields": "f2,f3,f12,f14",
                               "secids": "1.000001,0.399001"},
                       referer="https://quote.eastmoney.com/", ttl=60)),
        lambda d: bool((d.get("data") or {}).get("diff"))),
    "板块列表": lambda: (
        _json(HTTP.get("https://push2.eastmoney.com/api/qt/clist/get",
                       params={"pn": 1, "pz": 3, "fltt": 2, "invt": 2, "fid": "f3",
                               "fs": "m:90+t:2+f:!50", "fields": "f3,f12,f14"},
                       referer="https://quote.eastmoney.com/", ttl=300)),
        lambda d: bool((d.get("data") or {}).get("diff"))),
    "全市场搜索": lambda: (
        _json(HTTP.get("https://searchapi.eastmoney.com/api/suggest/get",
                       params={"input": "英伟达", "type": 14,
                               "token": "D43BF722C8E33BDC906FB84D85E326E8", "count": 3},
                       referer="https://www.eastmoney.com/", ttl=300)),
        lambda d: bool((d.get("QuotationCodeTable") or {}).get("Data"))),
}
QUICK = ["基金净值(移动端)", "PCF(上交所)", "个股日线(腾讯)", "国债期货(新浪日线)",
         "上金所 AU9999", "国际金价 XAU", "实时行情批量", "分红送配"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true")
    args = ap.parse_args()
    names = QUICK if args.quick else list(PROBES)
    log.info("=" * 72)
    log.info("接口探针：%d 项", len(names))
    log.info("=" * 72)
    ok_n = 0
    results = []
    for name in names:
        probe = PROBES[name]          # 返回 (data, check_fn) 的 lambda
        t0 = time.time()
        try:
            data, check = probe()
            ok = bool(check(data))
            el = time.time() - t0
            results.append((name, ok, el, ""))
            log.info("  %s %-22s %6.2fs", "✅" if ok else "⚠️ 字段缺失", name, el)
            ok_n += ok
        except Exception as e:  # noqa: BLE001
            el = time.time() - t0
            results.append((name, False, el, str(e)[:60]))
            log.info("  ❌ %-22s %6.2fs  %s", name, el, str(e)[:60])
    log.info("=" * 72)
    log.info("通过 %d/%d", ok_n, len(names))
    con = db.connect()
    con.execute("""CREATE TABLE IF NOT EXISTS probe_log(
        probe_date TEXT, name TEXT, ok INTEGER, seconds REAL, error TEXT,
        PRIMARY KEY(probe_date, name))""")
    con.executemany("INSERT OR REPLACE INTO probe_log VALUES(?,?,?,?,?)",
                    [(date.today().isoformat(), n, 1 if o else 0, round(s, 2), e)
                     for n, o, s, e in results])
    con.commit(); con.close()
    sys.exit(0 if ok_n >= len(names) * 0.8 else 1)


if __name__ == "__main__":
    main()
