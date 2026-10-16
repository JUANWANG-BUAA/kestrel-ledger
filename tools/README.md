# 工具链（22 个脚本）

## 本地数据与版本控制
`data/research.db` 是可重建的公开市场研究库，`data/cache/` 保存采集器的 HTTP 响应缓存；两者均为本机数据，不纳入 Git。首次运行研究采集脚本时，`tools/mkt/db.py` 会建立数据库结构；需要外部数据时，按下方采集命令运行，并先检查来源和影响范围。用户账本、私有持仓和凭据不得写入这些研究目录。

## 一键入口
| 命令 | 作用 |
|---|---|
| `python3 tools/daily.py --pre` | 盘前：采 PCF（当日成分） |
| `python3 tools/daily.py --post` | 盘后：PCF复核 → 合成指数 → 成分股增量 → 基金增量 → 归档清理 → 自检 |
| `python3 tools/daily.py --weekly` | 周任务：分红增量（复权修正）+ 精度回归 |
| `python3 tools/ci_check.py` | CI 门禁：健康 + 探针 + 精度，阻断性失败退出码 1 |
| `bash tools/install_cron.sh` | 安装定时任务（已挂载） |

## 数据采集
| 脚本 | 说明 |
|---|---|
| `collect.py` | 基金净值/画像/重仓/资产配置（60 只） |
| `collect_pcf.py` | **上交所 ETF 申赎清单**（27 只 ETF / 2177 成分股） |
| `pcf_synth.py` | PCF → 合成主题指数（+ 90 天归档清理） |
| `fetch_quotes.py` | 并发行情采集（`-j 8`，2.7 只/秒） |
| `adjust.py` | **分红送配 → 复权修正**（1823 只 / 24,835 条） |
| `trade_calendar.py` | 交易日历（从指数反推） |

## 算法与研究
| 脚本 | 说明 |
|---|---|
| `engine.py` | 估值引擎核心（NNLS / Ridge / 漂移修正 / 校准） |
| `quant.py` | 量化工具箱（卡尔曼 / RLS / 弹性网 / Huber / Hedge / 变点） |
| `quant_bench.py` | 量化方法对比跑分（T1 基线 vs Q1~Q6，5 品类） |
| `doc_check.py` | **文档一致性检查器**（引用/表/数字/命名/交叉引用） |
| `clean.py` | **数据清洗**（小数点错位/份额折算/异常值，官方净值锚点法） |
| `credit_spread.py` | 信用利差代理（债券 ETF 收益差，无需中债接口） |
| `brinson.py` | Brinson 三因子归因（配置/选择/交互） |
| `benchmark.py` | 全品类基准跑分（T0/T1/T2 + 路由） |
| `bond_bench.py` | 债券 7 算法交叉验证 |
| `intraday_bench.py` | 盘中收敛性验证 |
| `backtest.py` / `backtest_final.py` | 基金估值回测（含滞后敏感性） |
| `gold_chain.py` | 国际金价 → 国内金价 → 银行积存金 误差传递 |

## 分析与归档
| 脚本 | 说明 |
|---|---|
| `analysis.py` | 风险指标 / 归因 / 穿透 / 协方差收缩 / 风险贡献 |
| `theme_exposure.py` | 主题暴露度（用 PCF 合成指数回归） |
| `archive.py` | LTTB 降采样 / 冷热迁移 / 数据版本化 / VACUUM |
| `healthcheck.py` | 6 项数据健康自检 |
| `probe.py` | 16 项接口探针 |

## 数据源模块（`tools/mkt/sources/`）
`eastmoney` `tencent` `sina` `sge`（上金所） `sse_pcf`（上交所PCF） `szse`（深交所）

## ⚠️ 命名规范
**工具脚本禁止与 Python 标准库同名**（`calendar`/`json`/`time`/`random`/`types`/`email`/`code`/`copy`/`io`/`re`/`ast`/`token`/`test`）。
`tools/` 在 `sys.path` 上，同名会遮蔽标准库导致全线崩溃（曾因 `calendar.py` 踩坑）。

## 完整数据表清单（22 张）

### 公开数据（可重建，明文）
| 表 | 行数 | 说明 |
|---|---|---|
| `fund_meta` | 60 | 基金基础信息（名称/类型） |
| `fund_nav` | 150,636 | 历史净值（永久） |
| `fund_holding` | 2,617 | 季报前十大重仓 |
| `fund_alloc` | 200 | 资产配置（股票占比） |
| `holding_industry` | 188 | 持仓股→行业归属 |
| `stock_daily` | 1,836,070 | 个股日线（**不复权，append-only**） |
| `stock_dividend` | 24,835 | 分红送配（复权修正源） |
| `pcf_meta` | 27 | ETF 申赎清单元信息 |
| `pcf_component` | 3,022 | PCF 成分股（**保留 90 天**） |
| `pcf_weight` | 2,965 | 成分股权重（保留 90 天） |
| `pcf_synthetic_index` | 22 | **PCF 合成主题指数（永久）** |
| `trading_calendar` | 970 | 交易日历（A股 900 交易日 + 69 假日） |
| `intraday30` / `intraday30b` | 3,520 / 4,092 | 债券ETF / 国债期货 30 分钟线 |

### 派生与运维（可重算）
| 表 | 说明 |
|---|---|
| `bar_5min` | LTTB 降采样后的 5 分钟线 |
| `downsample_cursor` | 降采样断点 |
| `data_version` | **数据版本指纹**（可复现性） |
| `data_revision` | 数据修订记录（替代 in-place 修改） |
| `archive_log` | 归档任务日志 |
| `probe_log` | 接口探针结果 |
| `collect_log` | 采集任务日志 |
