package cn.market.assistant

import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.domain.portfolio.AssetInfo
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.AccountCashEvent
import cn.market.assistant.domain.portfolio.AccountCashEventType
import cn.market.assistant.domain.portfolio.AccountCashLedger
import cn.market.assistant.domain.portfolio.AccountCashRepository
import cn.market.assistant.domain.portfolio.PortfolioAnalytics
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.PositionSnapshot
import cn.market.assistant.domain.portfolio.Trade
import cn.market.assistant.domain.portfolio.TradeSide

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class MarketBar(val day: String, val close: Double)

class Ledger(context: Context) : SQLiteOpenHelper(context, "market_ledger.db", null, 11), AccountCashRepository {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
        seedCatalog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE trades RENAME TO trades_v1")
            db.execSQL("ALTER TABLE quotes RENAME TO quotes_v1")
            createSchema(db)
            seedCatalog(db)
            db.execSQL("""INSERT OR IGNORE INTO assets(id,name,kind,category)
                SELECT symbol,symbol,kind,'旧流水' FROM trades_v1""")
            db.execSQL("""INSERT INTO transactions
                (asset_id,side,day,amount,units,unit_price,fee,status)
                SELECT symbol,side,day,
                    CASE WHEN side='BUY' THEN CAST(quantity AS REAL)*CAST(price AS REAL)+CAST(fee AS REAL)
                         ELSE CAST(quantity AS REAL)*CAST(price AS REAL) END,
                    quantity,price,fee,'CONFIRMED'
                FROM trades_v1""")
            db.execSQL("DROP TABLE trades_v1")
            db.execSQL("""INSERT OR REPLACE INTO quotes(asset_id,price,observed_at,source)
                SELECT symbol,price,observed_at,source FROM quotes_v1""")
            db.execSQL("DROP TABLE quotes_v1")
        }
        if (oldVersion < 3) {
            createQuoteHistory(db)
            db.execSQL("""INSERT OR IGNORE INTO quote_history(asset_id,price,observed_at,source)
                SELECT asset_id,price,observed_at,source FROM quotes""")
        }
        if (oldVersion < 4) {
            createMarketSchema(db)
            seedWatchlist(db)
        }
        if (oldVersion < 5) createMarketChartSchema(db)
        if (oldVersion < 6 && !hasColumn(db, "market_chart_bars", "volume")) {
            db.execSQL("ALTER TABLE market_chart_bars ADD COLUMN volume REAL NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) createPortfolioSnapshotSchema(db)
        if (oldVersion < 8) createAccountCashSchema(db)
        if (oldVersion < 9) addAccountValueSnapshotColumns(db)
        if (oldVersion < 10) addTwrBoundaryColumns(db)
        if (oldVersion < 11) addReversalAndSnapshotValidityColumns(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS assets (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, category TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id TEXT NOT NULL, side TEXT NOT NULL,
            day TEXT NOT NULL, amount TEXT NOT NULL, units TEXT, unit_price TEXT, fee TEXT NOT NULL,
            status TEXT NOT NULL, reversed_at TEXT, reversal_reason TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_asset_day ON transactions(asset_id,day)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS quotes (
            asset_id TEXT PRIMARY KEY, price TEXT NOT NULL, observed_at TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'MANUAL')""")
        createQuoteHistory(db)
        createMarketSchema(db)
        createMarketChartSchema(db)
        createPortfolioSnapshotSchema(db)
        createAccountCashSchema(db)
        seedWatchlist(db)
    }

    private fun createQuoteHistory(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS quote_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id TEXT NOT NULL, price TEXT NOT NULL,
            observed_at TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'MANUAL',
            UNIQUE(asset_id, observed_at))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_quote_history_asset_time ON quote_history(asset_id,observed_at)")
    }

    private fun createMarketSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS watchlist (
            secid TEXT PRIMARY KEY, added_at TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS market_quotes (
            secid TEXT PRIMARY KEY, name TEXT NOT NULL, price REAL NOT NULL, change REAL NOT NULL,
            change_pct REAL NOT NULL, quote_time INTEGER NOT NULL, fetched_at INTEGER NOT NULL,
            source TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS market_daily_bars (
            secid TEXT NOT NULL, trade_date TEXT NOT NULL, close REAL NOT NULL, cached_at INTEGER NOT NULL,
            PRIMARY KEY(secid,trade_date))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_market_bars_date ON market_daily_bars(secid,trade_date)")
    }

    private fun createMarketChartSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS market_chart_bars (
            secid TEXT NOT NULL, period TEXT NOT NULL, trade_date TEXT NOT NULL,
            open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL DEFAULT 0,
            cached_at INTEGER NOT NULL, PRIMARY KEY(secid,period,trade_date))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_market_chart_time ON market_chart_bars(secid,period,trade_date)")
    }

    private fun createPortfolioSnapshotSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS portfolio_snapshots (
            day TEXT PRIMARY KEY, observed_at TEXT NOT NULL, position_count INTEGER NOT NULL,
            priced_position_count INTEGER NOT NULL, market_value TEXT, cost TEXT NOT NULL,
            holding_profit TEXT, realized_profit TEXT NOT NULL, total_profit TEXT,
            cash_balance TEXT, total_value TEXT, external_net_flow TEXT,
            valuation_fresh INTEGER NOT NULL DEFAULT 0, valid INTEGER NOT NULL DEFAULT 1)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS position_snapshots (
            day TEXT NOT NULL, asset_id TEXT NOT NULL, units TEXT NOT NULL, cost TEXT NOT NULL,
            market_value TEXT, holding_profit TEXT, realized_profit TEXT NOT NULL,
            quote_observed_at TEXT, quote_source TEXT,
            PRIMARY KEY(day,asset_id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_position_snapshots_asset_day ON position_snapshots(asset_id,day)")
    }

    private fun addAccountValueSnapshotColumns(db: SQLiteDatabase) {
        if (!hasColumn(db, "portfolio_snapshots", "cash_balance")) db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN cash_balance TEXT")
        if (!hasColumn(db, "portfolio_snapshots", "total_value")) db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN total_value TEXT")
        if (!hasColumn(db, "portfolio_snapshots", "external_net_flow")) db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN external_net_flow TEXT")
    }

    private fun addTwrBoundaryColumns(db: SQLiteDatabase) {
        if (!hasColumn(db, "portfolio_snapshots", "valuation_fresh")) {
            db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN valuation_fresh INTEGER NOT NULL DEFAULT 0")
        }
        if (!hasColumn(db, "account_cash_events", "occurred_at")) db.execSQL("ALTER TABLE account_cash_events ADD COLUMN occurred_at TEXT")
        if (!hasColumn(db, "account_cash_events", "portfolio_value_before")) db.execSQL("ALTER TABLE account_cash_events ADD COLUMN portfolio_value_before TEXT")
        if (!hasColumn(db, "account_cash_events", "valuation_complete")) {
            db.execSQL("ALTER TABLE account_cash_events ADD COLUMN valuation_complete INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun addReversalAndSnapshotValidityColumns(db: SQLiteDatabase) {
        if (!hasColumn(db, "transactions", "reversed_at")) db.execSQL("ALTER TABLE transactions ADD COLUMN reversed_at TEXT")
        if (!hasColumn(db, "transactions", "reversal_reason")) db.execSQL("ALTER TABLE transactions ADD COLUMN reversal_reason TEXT")
        if (!hasColumn(db, "portfolio_snapshots", "valid")) {
            db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN valid INTEGER NOT NULL DEFAULT 1")
        }
    }

    private fun createAccountCashSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS account_cash_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, type TEXT NOT NULL,
            amount TEXT NOT NULL, memo TEXT NOT NULL DEFAULT '', transaction_id INTEGER UNIQUE,
            occurred_at TEXT, portfolio_value_before TEXT, valuation_complete INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_account_cash_day ON account_cash_events(day,id)")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) return@use true
            false
        }

    private fun quotesFreshAt(positions: List<Position>, at: Instant): Boolean = positions.all { position ->
        val quoteTime = position.quoteTime?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        !quoteTime.isAfter(at.plusSeconds(5 * 60)) &&
            java.time.Duration.between(quoteTime, at) <= java.time.Duration.ofHours(72)
    }

    fun chartBars(secid: String, period: ChartPeriod): List<MarketOhlcBar> = buildList {
        readableDatabase.rawQuery("SELECT trade_date,open,close,high,low,volume FROM market_chart_bars WHERE secid=? AND period=? ORDER BY trade_date",
            arrayOf(secid, period.cacheKey)).use { c ->
            while (c.moveToNext()) add(MarketOhlcBar(c.getString(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)))
        }
    }

    fun chartBarsCachedAt(secid: String, period: ChartPeriod): Long = readableDatabase.rawQuery(
        "SELECT MAX(cached_at) FROM market_chart_bars WHERE secid=? AND period=?", arrayOf(secid, period.cacheKey)
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun saveChartBars(secid: String, period: ChartPeriod, bars: List<MarketOhlcBar>, cachedAt: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            bars.forEach { bar -> db.insertWithOnConflict("market_chart_bars", null, ContentValues().apply {
                put("secid", secid); put("period", period.cacheKey); put("trade_date", bar.day)
                put("open", bar.open); put("high", bar.high); put("low", bar.low); put("close", bar.close); put("volume", bar.volume); put("cached_at", cachedAt)
            }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun seedWatchlist(db: SQLiteDatabase) {
        if (db.rawQuery("SELECT COUNT(*) FROM watchlist", null).use { it.moveToFirst() && it.getInt(0) > 0 }) return
        val initial = listOf("1.000001", "0.399001", "100.NDX", "100.SPX", "100.N225", "100.HSI")
        db.beginTransaction()
        try {
            initial.forEach { secid -> db.insertWithOnConflict("watchlist", null, ContentValues().apply {
                put("secid", secid); put("added_at", Instant.now().toString())
            }, SQLiteDatabase.CONFLICT_IGNORE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun watchlist(): List<String> = buildList {
        readableDatabase.rawQuery("SELECT secid FROM watchlist ORDER BY added_at,secid", null).use { c ->
            while (c.moveToNext()) add(c.getString(0))
        }
    }

    fun setWatched(secid: String, watched: Boolean) {
        require(MarketInstrumentCatalog.byId.containsKey(secid)) { "暂不支持这个自选标的" }
        if (watched) writableDatabase.insertWithOnConflict("watchlist", null, ContentValues().apply {
            put("secid", secid); put("added_at", Instant.now().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE) else writableDatabase.delete("watchlist", "secid=?", arrayOf(secid))
    }

    fun marketQuotes(): Map<String, MarketQuote> = buildMap {
        readableDatabase.rawQuery("SELECT secid,name,price,change,change_pct,quote_time,fetched_at,source FROM market_quotes", null).use { c ->
            while (c.moveToNext()) put(c.getString(0), MarketQuote(c.getString(0), c.getString(1), c.getDouble(2),
                c.getDouble(3), c.getDouble(4), c.getLong(5), c.getLong(6), c.getString(7)))
        }
    }

    fun saveMarketQuotes(quotes: List<MarketQuote>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            quotes.forEach { quote -> db.insertWithOnConflict("market_quotes", null, ContentValues().apply {
                put("secid", quote.secid); put("name", quote.name); put("price", quote.price)
                put("change", quote.change); put("change_pct", quote.changePct)
                put("quote_time", quote.quoteTime); put("fetched_at", quote.fetchedAt); put("source", quote.source)
            }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun marketBars(secid: String): List<MarketBar> = buildList {
        readableDatabase.rawQuery("SELECT trade_date,close FROM market_daily_bars WHERE secid=? ORDER BY trade_date", arrayOf(secid)).use { c ->
            while (c.moveToNext()) add(MarketBar(c.getString(0), c.getDouble(1)))
        }
    }

    fun marketBarsCachedAt(secid: String): Long = readableDatabase.rawQuery(
        "SELECT MAX(cached_at) FROM market_daily_bars WHERE secid=?", arrayOf(secid)
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun saveMarketBars(secid: String, bars: List<MarketBar>, cachedAt: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            bars.forEach { bar -> db.insertWithOnConflict("market_daily_bars", null, ContentValues().apply {
                put("secid", secid); put("trade_date", bar.day); put("close", bar.close); put("cached_at", cachedAt)
            }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Store one replaceable observation per Shanghai calendar day, including explicit quote gaps. */
    fun savePortfolioSnapshot(positions: List<Position>, day: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))): PortfolioSnapshot {
        val observedAt = Instant.now().toString()
        val valuationFresh = day == LocalDate.now(ZoneId.of("Asia/Shanghai")) && quotesFreshAt(positions, Instant.parse(observedAt))
        val cashEvents = accountCashEvents()
        val cashBalance = AccountCashLedger.balance(cashEvents, day)?.amount
        val externalNetFlow = cashEvents.asSequence().filter { it.day == day }
            .filter { it.type == AccountCashEventType.DEPOSIT || it.type == AccountCashEventType.WITHDRAWAL }
            .fold(BigDecimal.ZERO) { sum, event -> sum + event.balanceDelta }
            .takeIf { cashBalance != null }
        val (snapshot, rows) = PortfolioAnalytics.capture(day, observedAt, positions, cashBalance, externalNetFlow, valuationFresh)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("portfolio_snapshots", null, ContentValues().apply {
                put("day", snapshot.day.toString()); put("observed_at", snapshot.observedAt)
                put("position_count", snapshot.positionCount); put("priced_position_count", snapshot.pricedPositionCount)
                put("market_value", snapshot.marketValue?.toPlainString()); put("cost", snapshot.cost.toPlainString())
                put("holding_profit", snapshot.holdingProfit?.toPlainString()); put("realized_profit", snapshot.realizedProfit.toPlainString())
                put("total_profit", snapshot.totalProfit?.toPlainString()); put("cash_balance", snapshot.cashBalance?.toPlainString())
                put("total_value", snapshot.totalValue?.toPlainString()); put("external_net_flow", snapshot.externalNetFlow?.toPlainString())
                put("valuation_fresh", if (snapshot.valuationFresh) 1 else 0); put("valid", 1)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("position_snapshots", "day=?", arrayOf(snapshot.day.toString()))
            rows.forEach { row -> db.insertOrThrow("position_snapshots", null, ContentValues().apply {
                put("day", row.day.toString()); put("asset_id", row.asset.symbol); put("units", row.units.toPlainString())
                put("cost", row.cost.toPlainString()); put("market_value", row.marketValue?.toPlainString())
                put("holding_profit", row.holdingProfit?.toPlainString()); put("realized_profit", row.realizedProfit.toPlainString())
                put("quote_observed_at", row.quoteObservedAt); put("quote_source", row.quoteSource)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return snapshot
    }

    fun portfolioSnapshots(): List<PortfolioSnapshot> = buildList {
        readableDatabase.rawQuery("""SELECT day,observed_at,position_count,priced_position_count,
            market_value,cost,holding_profit,realized_profit,total_profit,cash_balance,total_value,external_net_flow,valuation_fresh,valid
            FROM portfolio_snapshots ORDER BY day""", null).use { c ->
            while (c.moveToNext()) add(PortfolioSnapshot(
                LocalDate.parse(c.getString(0)), c.getString(1), c.getInt(2), c.getInt(3),
                c.getString(4)?.let(::BigDecimal), BigDecimal(c.getString(5)), c.getString(6)?.let(::BigDecimal),
                BigDecimal(c.getString(7)), c.getString(8)?.let(::BigDecimal),
                c.getString(9)?.let(::BigDecimal), c.getString(10)?.let(::BigDecimal), c.getString(11)?.let(::BigDecimal),
                c.getInt(12) == 1, c.getInt(13) == 1
            ))
        }
    }

    fun positionSnapshots(): Map<LocalDate, List<PositionSnapshot>> {
        val assetMap = assets().associateBy { it.symbol }
        val rows = linkedMapOf<LocalDate, MutableList<PositionSnapshot>>()
        readableDatabase.rawQuery("""SELECT s.day,s.asset_id,a.name,a.kind,a.category,s.units,s.cost,
            s.market_value,s.holding_profit,s.realized_profit,s.quote_observed_at,s.quote_source
            FROM position_snapshots s LEFT JOIN assets a ON a.id=s.asset_id ORDER BY s.day,s.asset_id""", null).use { c ->
            while (c.moveToNext()) {
                val day = LocalDate.parse(c.getString(0))
                val id = c.getString(1)
                val asset = assetMap[id] ?: AssetInfo(id, c.getString(2) ?: id,
                    runCatching { AssetKind.valueOf(c.getString(3)) }.getOrDefault(AssetKind.FUND), c.getString(4) ?: "历史标的")
                rows.getOrPut(day) { mutableListOf() } += PositionSnapshot(
                    day, asset, BigDecimal(c.getString(5)), BigDecimal(c.getString(6)), c.getString(7)?.let(::BigDecimal),
                    c.getString(8)?.let(::BigDecimal), BigDecimal(c.getString(9)), c.getString(10), c.getString(11)
                )
            }
        }
        return rows
    }

    private fun seedCatalog(db: SQLiteDatabase) {
        val items = JSONArray(appContext.assets.open("asset_catalog.json").bufferedReader().use { it.readText() })
        db.beginTransaction()
        try {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val values = ContentValues().apply {
                    put("id", item.getString("symbol")); put("name", item.getString("name"))
                    put("kind", item.getString("kind")); put("category", item.getString("category"))
                }
                db.insertWithOnConflict("assets", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun assets(kind: AssetKind? = null): List<AssetInfo> {
        val result = mutableListOf<AssetInfo>()
        val sql = if (kind == null) "SELECT id,name,kind,category FROM assets ORDER BY kind,name"
            else "SELECT id,name,kind,category FROM assets WHERE kind=? ORDER BY name"
        readableDatabase.rawQuery(sql, if (kind == null) null else arrayOf(kind.name)).use { c ->
            while (c.moveToNext()) result += AssetInfo(c.getString(0), c.getString(1), AssetKind.valueOf(c.getString(2)), c.getString(3))
        }
        return result
    }

    fun findAsset(id: String): AssetInfo? = assets().firstOrNull { it.symbol == id }

    fun addCustomAsset(symbol: String, name: String, kind: AssetKind): AssetInfo {
        val asset = AssetInfo(symbol.trim().uppercase(), name.trim(), kind, if (kind == AssetKind.FUND) "自定义基金" else "黄金")
        require(asset.symbol.isNotBlank() && asset.name.isNotBlank()) { "请填写名称和代码" }
        writableDatabase.insertOrThrow("assets", null, ContentValues().apply {
            put("id",asset.symbol); put("name",asset.name); put("kind",asset.kind.name); put("category",asset.category)
        })
        return asset
    }

    fun trades(): List<Trade> {
        val result = mutableListOf<Trade>()
        readableDatabase.rawQuery("""SELECT t.id,a.id,a.name,a.kind,a.category,t.side,t.day,
            t.amount,t.units,t.unit_price,t.fee,t.status,t.reversed_at,t.reversal_reason FROM transactions t
            JOIN assets a ON a.id=t.asset_id ORDER BY t.day DESC,t.id DESC""", null).use { c ->
            while (c.moveToNext()) result += Trade(
                c.getLong(0), AssetInfo(c.getString(1),c.getString(2),AssetKind.valueOf(c.getString(3)),c.getString(4)),
                TradeSide.valueOf(c.getString(5)),c.getString(6),BigDecimal(c.getString(7)),
                c.getString(8)?.let(::BigDecimal),c.getString(9)?.let(::BigDecimal),BigDecimal(c.getString(10)),
                c.getString(11)=="CONFIRMED", c.getString(12), c.getString(13)
            )
        }
        return result
    }

    fun positions(): List<Position> {
        val quoteMap = mutableMapOf<String, Triple<BigDecimal,String,String>>()
        readableDatabase.rawQuery("SELECT asset_id,price,observed_at,source FROM quotes", null).use { c ->
            while (c.moveToNext()) quoteMap[c.getString(0)] = Triple(BigDecimal(c.getString(1)), c.getString(2), c.getString(3))
        }
        return computePositions(trades(), quoteMap)
    }

    override fun accountCashEvents(): List<AccountCashEvent> = buildList {
        readableDatabase.rawQuery("SELECT id,day,type,amount,memo,transaction_id,occurred_at,portfolio_value_before,valuation_complete FROM account_cash_events ORDER BY day,id", null).use { c ->
            while (c.moveToNext()) add(AccountCashEvent(
                c.getLong(0), LocalDate.parse(c.getString(1)), AccountCashEventType.valueOf(c.getString(2)),
                BigDecimal(c.getString(3)), c.getString(4), if (c.isNull(5)) null else c.getLong(5),
                if (c.isNull(6)) null else c.getString(6), c.getString(7)?.let(::BigDecimal), c.getInt(8) == 1
            ))
        }
    }

    override fun accountCashBalance(asOf: LocalDate?) = AccountCashLedger.balance(accountCashEvents(), asOf)

    /** Starts explicit cash tracking at a cutover date; older trades are intentionally not replayed. */
    override fun initializeAccountCash(openingBalance: BigDecimal, day: LocalDate) {
        require(openingBalance >= BigDecimal.ZERO) { "期初现金不能小于零" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(accountCashEvents().isEmpty()) { "现金账本已有流水" }
            db.insertOrThrow("account_cash_events", null, ContentValues().apply {
                put("day", day.toString()); put("type", AccountCashEventType.OPENING_BALANCE.name)
                put("amount", openingBalance.toPlainString()); put("memo", "期初现金")
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Records an external deposit or withdrawal. Withdrawals cannot exceed available cash. */
    override fun addExternalCashMovement(
        type: AccountCashEventType,
        amount: BigDecimal,
        memo: String,
        day: LocalDate
    ) {
        require(type == AccountCashEventType.DEPOSIT || type == AccountCashEventType.WITHDRAWAL) { "这里只能记录入金或出金" }
        require(amount > BigDecimal.ZERO) { "金额必须大于零" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val events = accountCashEvents()
            val balance = requireNotNull(AccountCashLedger.balance(events)) { "请先设置现金跟踪的期初余额" }
            require(!day.isBefore(balance.trackingStart)) { "流水日期不能早于现金跟踪起点" }
            val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            val valuationObservedAt = if (day == today) Instant.now() else null
            val flowPositions = if (valuationObservedAt != null) positions() else emptyList()
            val valuationComplete = valuationObservedAt != null && quotesFreshAt(flowPositions, valuationObservedAt)
            val valueBefore = if (valuationComplete) balance.amount + flowPositions.fold(BigDecimal.ZERO) { sum, position ->
                sum + requireNotNull(position.value)
            } else null
            val occurredAt = valuationObservedAt?.let { Instant.now() }
            val proposed = AccountCashEvent(Long.MAX_VALUE, day, type, amount, memo.trim(),
                occurredAt = occurredAt?.toString(), portfolioValueBefore = valueBefore, valuationComplete = valuationComplete)
            AccountCashLedger.balance(events + proposed)
            db.insertOrThrow("account_cash_events", null, ContentValues().apply {
                put("day", day.toString()); put("type", type.name); put("amount", amount.toPlainString())
                put("memo", memo.trim())
                put("occurred_at", occurredAt?.toString()); put("portfolio_value_before", valueBefore?.toPlainString())
                put("valuation_complete", if (valuationComplete) 1 else 0)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun recordTradeCashEvent(db: SQLiteDatabase, transactionId: Long, day: String, side: TradeSide,
                                     amount: BigDecimal, fee: BigDecimal) {
        require(side != TradeSide.OPENING_POSITION) { "期初持仓不改变现金余额" }
        val type = if (side == TradeSide.BUY) AccountCashEventType.TRADE_BUY else AccountCashEventType.TRADE_SELL
        val balance = AccountCashLedger.balance(accountCashEvents()) ?: return
        val tradeDay = LocalDate.parse(day)
        if (tradeDay.isBefore(balance.trackingStart)) return
        val cashAmount = if (side == TradeSide.BUY) amount else amount - fee
        require(cashAmount > BigDecimal.ZERO) { "扣除手续费后的成交金额必须大于零" }
        if (side == TradeSide.BUY) require(balance.amount >= cashAmount) { "现金余额不足，请先记录入金或调整期初现金" }
        db.insertOrThrow("account_cash_events", null, ContentValues().apply {
            put("day", day); put("type", type.name); put("amount", cashAmount.toPlainString())
            put("memo", if (side == TradeSide.BUY) "买入成交" else "卖出到账"); put("transaction_id", transactionId)
        })
    }

    fun addTrade(asset: AssetInfo, side: TradeSide, amount: BigDecimal, units: BigDecimal?,
                 price: BigDecimal?, fee: BigDecimal): Long {
        require(side != TradeSide.OPENING_POSITION) { "期初持仓请使用专用录入方式" }
        require(fee >= BigDecimal.ZERO)
        require(units == null || units > BigDecimal.ZERO)
        require(price == null || price > BigDecimal.ZERO)
        if (side == TradeSide.BUY) require(amount > BigDecimal.ZERO && fee < amount) { "买入金额需大于手续费" }
        if (side == TradeSide.SELL) {
            val position = positions().firstOrNull { it.asset.symbol == asset.symbol }
            require(units != null && position != null && position.units >= units) { "卖出份额超过当前持仓" }
        }
        val sellAmount = if (side == TradeSide.SELL && price != null) requireNotNull(units).multiply(price) else amount
        val confirmed = units != null && price != null
        val values = ContentValues().apply {
            put("asset_id", asset.symbol); put("side", side.name); put("day", LocalDate.now().toString())
            put("amount", sellAmount.toPlainString()); put("units", units?.toPlainString())
            put("unit_price", price?.toPlainString()); put("fee", fee.toPlainString())
            put("status", if (confirmed) "CONFIRMED" else "PENDING")
        }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val transactionId = db.insertOrThrow("transactions", null, values)
            if (confirmed) recordTradeCashEvent(db, transactionId, LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), side,
                if (side == TradeSide.SELL) sellAmount else amount, fee)
            db.setTransactionSuccessful()
            transactionId
        } finally { db.endTransaction() }
    }

    /** Records an existing holding at the user's cost basis without inventing a past purchase or cash flow. */
    fun addOpeningPosition(asset: AssetInfo, units: BigDecimal, totalCost: BigDecimal): Long {
        require(units > BigDecimal.ZERO) { "持有份额必须大于零" }
        require(totalCost > BigDecimal.ZERO) { "历史成本必须大于零" }
        val averageCost = totalCost.divide(units, 12, RoundingMode.HALF_UP)
        require(averageCost > BigDecimal.ZERO) { "平均成本过小，无法精确保存" }
        val day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val transactionId = db.insertOrThrow("transactions", null, ContentValues().apply {
                put("asset_id", asset.symbol)
                put("side", TradeSide.OPENING_POSITION.name)
                put("day", day)
                put("amount", totalCost.toPlainString())
                put("units", units.toPlainString())
                put("unit_price", averageCost.toPlainString())
                put("fee", BigDecimal.ZERO.toPlainString())
                put("status", "CONFIRMED")
            })
            db.setTransactionSuccessful()
            transactionId
        } finally { db.endTransaction() }
    }

    /** Undo a just-recorded trade and its linked cash event atomically. */
    fun deleteTrade(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val trade = trades().singleOrNull { it.id == id } ?: error("找不到这笔流水")
            require(!trade.isReversed) { "已冲正的流水需要保留审计记录" }
            require(db.delete("transactions", "id=?", arrayOf(id.toString())) == 1) { "找不到这笔流水" }
            db.delete("account_cash_events", "transaction_id=?", arrayOf(id.toString()))
            positions() // Reject an undo that would leave a later sale above the remaining position.
            AccountCashLedger.balance(accountCashEvents()) // Reject an undo that would make cash negative.
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Preserve the original transaction while excluding it from balances and calculations. */
    fun reverseTrade(id: Long, reason: String) {
        val note = reason.trim()
        require(note.isNotBlank()) { "请填写冲正原因" }
        require(note.length <= 200) { "冲正原因不能超过 200 个字符" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val trade = trades().singleOrNull { it.id == id } ?: error("找不到这笔流水")
            require(!trade.isReversed) { "这笔流水已经冲正" }
            val reversedAt = Instant.now().toString()
            val changed = db.update("transactions", ContentValues().apply {
                put("reversed_at", reversedAt)
                put("reversal_reason", note)
            }, "id=? AND reversed_at IS NULL", arrayOf(id.toString()))
            require(changed == 1) { "这笔流水状态已变化，请刷新后重试" }
            db.delete("account_cash_events", "transaction_id=?", arrayOf(id.toString()))

            // Validate the corrected ledger before committing the audit marker.
            positions()
            AccountCashLedger.balance(accountCashEvents())
            db.update("portfolio_snapshots", ContentValues().apply { put("valid", 0) },
                "day>=?", arrayOf(trade.day))
            db.update("account_cash_events", ContentValues().apply {
                putNull("portfolio_value_before")
                put("valuation_complete", 0)
            }, "day>=? AND type IN (?,?)", arrayOf(
                trade.day, AccountCashEventType.DEPOSIT.name, AccountCashEventType.WITHDRAWAL.name
            ))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun confirmTrade(id: Long, price: BigDecimal) {
        require(price > BigDecimal.ZERO)
        val trade = trades().firstOrNull { it.id == id } ?: error("找不到这笔流水")
        require(!trade.isReversed) { "已冲正流水不能确认" }
        require(!trade.confirmed) { "这笔流水已经确认" }
        val units = if (trade.side == TradeSide.BUY)
            trade.amount.subtract(trade.fee).divide(price, 8, RoundingMode.DOWN)
        else requireNotNull(trade.units)
        if (trade.side == TradeSide.SELL) {
            val held = positions().firstOrNull { it.asset.symbol == trade.asset.symbol }?.units ?: BigDecimal.ZERO
            require(held >= units) { "按该净值换算的赎回份额超过当前持仓" }
        }
        val amount = if (trade.side == TradeSide.SELL) units.multiply(price) else trade.amount
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("transactions", ContentValues().apply {
                put("amount", amount.toPlainString()); put("units", units.toPlainString())
                put("unit_price", price.toPlainString()); put("status", "CONFIRMED")
            }, "id=?", arrayOf(id.toString()))
            recordTradeCashEvent(db, id, trade.day, trade.side, amount, trade.fee)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun setQuote(asset: AssetInfo, price: BigDecimal) {
        require(price > BigDecimal.ZERO)
        val observedAt = Instant.now().toString()
        val values = ContentValues().apply {
            put("asset_id", asset.symbol); put("price", price.toPlainString())
            put("observed_at", observedAt); put("source", "MANUAL")
        }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertWithOnConflict("quotes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            writableDatabase.insertWithOnConflict("quote_history", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun exportBackup(): String {
        val root = JSONObject().put("format", "market-ledger-backup").put("version", 2)
            .put("exported_at", Instant.now().toString())
        val assetArray = JSONArray()
        assets().forEach { asset ->
            assetArray.put(JSONObject().put("id", asset.symbol).put("name", asset.name)
                .put("kind", asset.kind.name).put("category", asset.category))
        }
        root.put("assets", assetArray)
        root.put("watchlist", JSONArray().apply { watchlist().forEach(::put) })
        val transactionArray = JSONArray()
        trades().forEach { trade ->
            transactionArray.put(JSONObject().put("id", trade.id).put("asset_id", trade.asset.symbol)
                .put("side", trade.side.name).put("day", trade.day).put("amount", trade.amount.toPlainString())
                .put("units", trade.units?.toPlainString()).put("unit_price", trade.price?.toPlainString())
                .put("fee", trade.fee.toPlainString()).put("status", if (trade.confirmed) "CONFIRMED" else "PENDING")
                .put("reversed_at", trade.reversedAt ?: JSONObject.NULL)
                .put("reversal_reason", trade.reversalReason ?: JSONObject.NULL))
        }
        root.put("transactions", transactionArray)
        root.put("account_cash_events", JSONArray().apply {
            accountCashEvents().forEach { event -> put(JSONObject().put("id", event.id).put("day", event.day.toString())
                .put("type", event.type.name).put("amount", event.amount.toPlainString()).put("memo", event.memo)
                .put("transaction_id", event.transactionId ?: JSONObject.NULL)
                .put("occurred_at", event.occurredAt ?: JSONObject.NULL)
                .put("portfolio_value_before", event.portfolioValueBefore?.toPlainString() ?: JSONObject.NULL)
                .put("valuation_complete", event.valuationComplete)) }
        })
        val quoteArray = JSONArray()
        readableDatabase.rawQuery("SELECT asset_id,price,observed_at,source FROM quotes ORDER BY asset_id", null).use { c ->
            while (c.moveToNext()) quoteArray.put(JSONObject().put("asset_id", c.getString(0))
                .put("price", c.getString(1)).put("observed_at", c.getString(2)).put("source", c.getString(3)))
        }
        root.put("latest_quotes", quoteArray)
        val historyArray = JSONArray()
        readableDatabase.rawQuery("SELECT asset_id,price,observed_at,source FROM quote_history ORDER BY observed_at,id", null).use { c ->
            while (c.moveToNext()) historyArray.put(JSONObject().put("asset_id", c.getString(0))
                .put("price", c.getString(1)).put("observed_at", c.getString(2)).put("source", c.getString(3)))
        }
        root.put("quote_history", historyArray)
        val snapshots = JSONArray()
        portfolioSnapshots().forEach { snapshot ->
            snapshots.put(JSONObject().put("day", snapshot.day.toString()).put("observed_at", snapshot.observedAt)
                .put("position_count", snapshot.positionCount).put("priced_position_count", snapshot.pricedPositionCount)
                .put("market_value", snapshot.marketValue?.toPlainString() ?: JSONObject.NULL)
                .put("cost", snapshot.cost.toPlainString()).put("holding_profit", snapshot.holdingProfit?.toPlainString() ?: JSONObject.NULL)
                .put("realized_profit", snapshot.realizedProfit.toPlainString()).put("total_profit", snapshot.totalProfit?.toPlainString() ?: JSONObject.NULL)
                .put("cash_balance", snapshot.cashBalance?.toPlainString() ?: JSONObject.NULL)
                .put("total_value", snapshot.totalValue?.toPlainString() ?: JSONObject.NULL)
                .put("external_net_flow", snapshot.externalNetFlow?.toPlainString() ?: JSONObject.NULL)
                .put("valuation_fresh", snapshot.valuationFresh).put("valid", snapshot.valid))
        }
        root.put("portfolio_snapshots", snapshots)
        val positionRows = JSONArray()
        positionSnapshots().values.flatten().forEach { row ->
            positionRows.put(JSONObject().put("day", row.day.toString()).put("asset_id", row.asset.symbol)
                .put("units", row.units.toPlainString()).put("cost", row.cost.toPlainString())
                .put("market_value", row.marketValue?.toPlainString() ?: JSONObject.NULL)
                .put("holding_profit", row.holdingProfit?.toPlainString() ?: JSONObject.NULL)
                .put("realized_profit", row.realizedProfit.toPlainString())
                .put("quote_observed_at", row.quoteObservedAt ?: JSONObject.NULL).put("quote_source", row.quoteSource ?: JSONObject.NULL))
        }
        root.put("position_snapshots", positionRows)
        return root.toString(2)
    }

    fun restoreBackup(json: String) {
        val root = JSONObject(json)
        val backupVersion = root.optInt("version")
        require(root.optString("format") == "market-ledger-backup" && backupVersion in 1..2) { "备份格式不受支持" }
        val restoredAssets = linkedMapOf<String, AssetInfo>()
        val assetArray = root.getJSONArray("assets")
        for (i in 0 until assetArray.length()) {
            val item = assetArray.getJSONObject(i)
            val asset = AssetInfo(item.getString("id").trim().uppercase(), item.getString("name").trim(),
                AssetKind.valueOf(item.getString("kind")), item.getString("category").trim())
            require(asset.symbol.isNotBlank() && asset.name.isNotBlank() && asset.category.isNotBlank()) { "备份中存在不完整标的" }
            require(restoredAssets.put(asset.symbol, asset) == null) { "备份中存在重复标的" }
        }
        val watchRows = root.optJSONArray("watchlist")
        val restoredWatchlist = if (watchRows == null) listOf("1.000001", "0.399001", "100.NDX", "100.SPX", "100.N225", "100.HSI")
            else (0 until watchRows.length()).map { watchRows.getString(it) }
        require(restoredWatchlist.distinct().size == restoredWatchlist.size &&
            restoredWatchlist.all(MarketInstrumentCatalog.byId::containsKey)) { "备份中的自选列表无效" }

        val restoredTrades = mutableListOf<Trade>()
        val transactionArray = root.getJSONArray("transactions")
        val transactionIds = mutableSetOf<Long>()
        for (i in 0 until transactionArray.length()) {
            val item = transactionArray.getJSONObject(i)
            val id = item.getLong("id")
            val assetId = item.getString("asset_id")
            val asset = restoredAssets[assetId] ?: error("流水引用了不存在的标的")
            val side = TradeSide.valueOf(item.getString("side"))
            val day = item.getString("day").also { LocalDate.parse(it) }
            val amount = BigDecimal(item.getString("amount"))
            val units = item.optString("units").takeIf { it.isNotBlank() && it != "null" }?.let(::BigDecimal)
            val price = item.optString("unit_price").takeIf { it.isNotBlank() && it != "null" }?.let(::BigDecimal)
            val fee = BigDecimal(item.getString("fee"))
            val status = item.getString("status")
            require(status == "CONFIRMED" || status == "PENDING") { "备份中存在未知流水状态" }
            val confirmed = status == "CONFIRMED"
            require(id > 0 && transactionIds.add(id) && amount >= BigDecimal.ZERO && fee >= BigDecimal.ZERO) { "备份中存在无效流水" }
            require(units == null || units > BigDecimal.ZERO)
            require(price == null || price > BigDecimal.ZERO)
            require(if (confirmed) units != null && price != null && amount > BigDecimal.ZERO else price == null) { "流水状态与价格数据不匹配" }
            if (side == TradeSide.BUY) require(amount > fee) { "买入金额必须大于手续费" }
            if (side == TradeSide.OPENING_POSITION) {
                require(confirmed && fee.compareTo(BigDecimal.ZERO) == 0) { "期初持仓必须已确认且不含交易手续费" }
            }
            val reversedAt = item.optString("reversed_at").takeIf { it.isNotBlank() && it != "null" }
                ?.also(Instant::parse)
            val reversalReason = item.optString("reversal_reason").takeIf { it.isNotBlank() && it != "null" }
            require((reversedAt == null) == (reversalReason == null)) { "冲正流水缺少时间或原因" }
            if (reversedAt != null) {
                val reason = requireNotNull(reversalReason)
                val reversalDay = Instant.parse(reversedAt).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
                require(reason.length <= 200 && reason.isNotBlank()) { "冲正原因无效" }
                require(!LocalDate.parse(day).isAfter(reversalDay)) { "冲正时间早于原交易日期" }
            }
            restoredTrades += Trade(id, asset, side, day, amount, units, price, fee, confirmed, reversedAt, reversalReason)
        }
        restoredTrades.sortWith(compareByDescending<Trade> { it.day }.thenByDescending { it.id })

        val restoredCashEvents = mutableListOf<AccountCashEvent>()
        val cashRows = root.optJSONArray("account_cash_events") ?: JSONArray()
        for (i in 0 until cashRows.length()) {
            val item = cashRows.getJSONObject(i)
            val type = AccountCashEventType.valueOf(item.getString("type"))
            val event = AccountCashEvent(
                item.getLong("id"), LocalDate.parse(item.getString("day")), type,
                BigDecimal(item.getString("amount")), item.optString("memo"),
                if (item.has("transaction_id") && !item.isNull("transaction_id")) item.getLong("transaction_id") else null,
                if (item.has("occurred_at") && !item.isNull("occurred_at")) item.getString("occurred_at").also(Instant::parse) else null,
                item.nullableDecimal("portfolio_value_before"), item.optBoolean("valuation_complete", false)
            )
            require(event.id > 0 && restoredCashEvents.none { it.id == event.id }) { "备份中存在重复现金流水" }
            if (event.occurredAt != null) {
                require(LocalDate.ofInstant(Instant.parse(event.occurredAt), ZoneId.of("Asia/Shanghai")) == event.day) {
                    "现金流水日期与发生时间不一致"
                }
            }
            if (event.transactionId != null) {
                val trade = restoredTrades.singleOrNull { it.id == event.transactionId && it.confirmed }
                    ?: error("现金流水引用了不存在或未确认的交易")
                require(!trade.isReversed) { "已冲正的交易不能保留关联现金流水" }
                require(trade.side != TradeSide.OPENING_POSITION) { "期初持仓不能关联交易现金流水" }
                val expectedType = if (trade.side == TradeSide.BUY) AccountCashEventType.TRADE_BUY else AccountCashEventType.TRADE_SELL
                val expectedAmount = if (trade.side == TradeSide.BUY) trade.amount else trade.amount - trade.fee
                require(event.type == expectedType && event.day.toString() == trade.day && event.amount.compareTo(expectedAmount) == 0) {
                    "交易现金流水与原交易不一致"
                }
            }
            restoredCashEvents += event
        }
        require(AccountCashLedger.balance(restoredCashEvents) != null || restoredCashEvents.isEmpty()) { "现金流水缺少期初余额" }

        val restoredQuotes = mutableMapOf<String, Triple<BigDecimal, String, String>>()
        val quoteRows = root.getJSONArray("latest_quotes")
        for (i in 0 until quoteRows.length()) {
            val item = quoteRows.getJSONObject(i)
            val assetId = item.getString("asset_id")
            require(restoredAssets.containsKey(assetId)) { "报价引用了不存在的标的" }
            val price = BigDecimal(item.getString("price")); require(price > BigDecimal.ZERO)
            val observedAt = item.getString("observed_at").also { Instant.parse(it) }
            val source = item.getString("source").also { require(it.isNotBlank()) }
            require(restoredQuotes.put(assetId, Triple(price, observedAt, source)) == null) { "备份中存在重复报价" }
        }
        val historyRows = root.getJSONArray("quote_history")
        val history = mutableListOf<JSONObject>()
        val historyKeys = mutableSetOf<Pair<String, String>>()
        for (i in 0 until historyRows.length()) {
            val item = historyRows.getJSONObject(i)
            val assetId = item.getString("asset_id")
            require(restoredAssets.containsKey(assetId)) { "历史报价引用了不存在的标的" }
            require(BigDecimal(item.getString("price")) > BigDecimal.ZERO)
            val observedAt = item.getString("observed_at").also { Instant.parse(it) }
            require(item.getString("source").isNotBlank())
            require(historyKeys.add(assetId to observedAt)) { "备份中存在重复历史报价" }
            history += item
        }
        computePositions(restoredTrades, restoredQuotes)

        val restoredPortfolioSnapshots = mutableListOf<PortfolioSnapshot>()
        val snapshotRows = root.optJSONArray("portfolio_snapshots") ?: JSONArray()
        for (i in 0 until snapshotRows.length()) {
            val item = snapshotRows.getJSONObject(i)
            val snapshot = PortfolioSnapshot(
                item.getString("day").let(LocalDate::parse), item.getString("observed_at").also { Instant.parse(it) },
                item.getInt("position_count"), item.getInt("priced_position_count"),
                item.nullableDecimal("market_value"), BigDecimal(item.getString("cost")),
                item.nullableDecimal("holding_profit"), BigDecimal(item.getString("realized_profit")), item.nullableDecimal("total_profit"),
                item.nullableDecimal("cash_balance"), item.nullableDecimal("total_value"), item.nullableDecimal("external_net_flow"),
                item.optBoolean("valuation_fresh", false), item.optBoolean("valid", true)
            )
            require(snapshot.positionCount >= 0 && snapshot.pricedPositionCount in 0..snapshot.positionCount) { "备份中存在无效快照覆盖率" }
            require(snapshot.cashBalance?.let { it >= BigDecimal.ZERO } != false) { "备份中存在负现金余额" }
            val cash = snapshot.cashBalance
            val securities = snapshot.marketValue
            val total = snapshot.totalValue
            if (cash != null && securities != null && total != null) {
                require(total.compareTo(cash.add(securities)) == 0) { "备份中的账户总资产与现金、市值不一致" }
            }
            require(restoredPortfolioSnapshots.none { it.day == snapshot.day }) { "备份中存在重复组合快照" }
            restoredPortfolioSnapshots += snapshot
        }
        val restoredPositionSnapshots = mutableListOf<JSONObject>()
        val positionSnapshotRows = root.optJSONArray("position_snapshots") ?: JSONArray()
        val snapshotKeys = mutableSetOf<Pair<String, String>>()
        for (i in 0 until positionSnapshotRows.length()) {
            val item = positionSnapshotRows.getJSONObject(i)
            val day = item.getString("day").also { LocalDate.parse(it) }
            val assetId = item.getString("asset_id")
            require(restoredAssets.containsKey(assetId)) { "持仓快照引用了不存在的标的" }
            require(BigDecimal(item.getString("units")) >= BigDecimal.ZERO && BigDecimal(item.getString("cost")) >= BigDecimal.ZERO)
            require(item.nullableDecimal("market_value")?.let { it >= BigDecimal.ZERO } != false)
            require(snapshotKeys.add(day to assetId)) { "备份中存在重复持仓快照" }
            restoredPositionSnapshots += item
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transactions", null, null)
            db.delete("account_cash_events", null, null)
            db.delete("quotes", null, null)
            db.delete("quote_history", null, null)
            db.delete("portfolio_snapshots", null, null)
            db.delete("position_snapshots", null, null)
            db.delete("watchlist", null, null)
            db.delete("assets", null, null)
            seedCatalog(db)
            restoredWatchlist.forEach { secid -> db.insertOrThrow("watchlist", null, ContentValues().apply {
                put("secid", secid); put("added_at", Instant.now().toString())
            }) }
            restoredAssets.values.forEach { asset ->
                db.insertWithOnConflict("assets", null, ContentValues().apply {
                    put("id", asset.symbol); put("name", asset.name); put("kind", asset.kind.name); put("category", asset.category)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            restoredTrades.forEach { trade ->
                db.insertOrThrow("transactions", null, ContentValues().apply {
                    put("id", trade.id); put("asset_id", trade.asset.symbol); put("side", trade.side.name); put("day", trade.day)
                    put("amount", trade.amount.toPlainString()); put("units", trade.units?.toPlainString())
                    put("unit_price", trade.price?.toPlainString()); put("fee", trade.fee.toPlainString())
                    put("status", if (trade.confirmed) "CONFIRMED" else "PENDING")
                    put("reversed_at", trade.reversedAt); put("reversal_reason", trade.reversalReason)
                })
            }
            restoredCashEvents.forEach { event -> db.insertOrThrow("account_cash_events", null, ContentValues().apply {
                put("id", event.id); put("day", event.day.toString()); put("type", event.type.name)
                put("amount", event.amount.toPlainString()); put("memo", event.memo)
                put("transaction_id", event.transactionId)
                put("occurred_at", event.occurredAt); put("portfolio_value_before", event.portfolioValueBefore?.toPlainString())
                put("valuation_complete", if (event.valuationComplete) 1 else 0)
            }) }
            quoteRowsFromBackup(root, "latest_quotes").forEach { row ->
                db.insertOrThrow("quotes", null, quoteValues(row))
            }
            history.forEach { row -> db.insertOrThrow("quote_history", null, quoteValues(row)) }
            restoredPortfolioSnapshots.forEach { snapshot -> db.insertOrThrow("portfolio_snapshots", null, ContentValues().apply {
                put("day", snapshot.day.toString()); put("observed_at", snapshot.observedAt)
                put("position_count", snapshot.positionCount); put("priced_position_count", snapshot.pricedPositionCount)
                put("market_value", snapshot.marketValue?.toPlainString()); put("cost", snapshot.cost.toPlainString())
                put("holding_profit", snapshot.holdingProfit?.toPlainString()); put("realized_profit", snapshot.realizedProfit.toPlainString())
                put("total_profit", snapshot.totalProfit?.toPlainString()); put("cash_balance", snapshot.cashBalance?.toPlainString())
                put("total_value", snapshot.totalValue?.toPlainString()); put("external_net_flow", snapshot.externalNetFlow?.toPlainString())
                put("valuation_fresh", if (snapshot.valuationFresh) 1 else 0); put("valid", if (snapshot.valid) 1 else 0)
            }) }
            restoredPositionSnapshots.forEach { row -> db.insertOrThrow("position_snapshots", null, ContentValues().apply {
                put("day", row.getString("day")); put("asset_id", row.getString("asset_id"))
                put("units", row.getString("units")); put("cost", row.getString("cost"))
                put("market_value", row.nullableDecimal("market_value")?.toPlainString())
                put("holding_profit", row.nullableDecimal("holding_profit")?.toPlainString())
                put("realized_profit", row.getString("realized_profit"))
                put("quote_observed_at", row.optString("quote_observed_at").takeIf { it.isNotBlank() && it != "null" })
                put("quote_source", row.optString("quote_source").takeIf { it.isNotBlank() && it != "null" })
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun quoteRowsFromBackup(root: JSONObject, key: String): List<JSONObject> {
        val rows = root.getJSONArray(key)
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private fun quoteValues(row: JSONObject) = ContentValues().apply {
        put("asset_id", row.getString("asset_id")); put("price", row.getString("price"))
        put("observed_at", row.getString("observed_at")); put("source", row.getString("source"))
    }
}

private fun JSONObject.nullableDecimal(key: String): BigDecimal? =
    if (!has(key) || isNull(key)) null else BigDecimal(getString(key))

private fun computePositions(
    trades: List<Trade>, quotes: Map<String, Triple<BigDecimal,String,String>>
): List<Position> {
    data class State(
        val asset: AssetInfo, var units: BigDecimal = BigDecimal.ZERO,
        var cost: BigDecimal = BigDecimal.ZERO, var realized: BigDecimal = BigDecimal.ZERO
    )
    val states = linkedMapOf<String, State>()
    for (trade in trades.asReversed()) {
        if (!trade.confirmed || trade.isReversed) continue
        val p = states.getOrPut(trade.asset.symbol) { State(trade.asset) }
        val units = requireNotNull(trade.units)
        val price = requireNotNull(trade.price)
        if (trade.side == TradeSide.BUY || trade.side == TradeSide.OPENING_POSITION) {
            p.units = p.units.add(units)
            p.cost = p.cost.add(trade.amount)
        } else {
            require(p.units >= units) { "流水记录存在超额卖出" }
            val costOut = if (p.units == units) p.cost else p.cost.multiply(units).divide(p.units, 12, RoundingMode.HALF_UP)
            p.units = p.units.subtract(units)
            p.cost = p.cost.subtract(costOut)
            p.realized = p.realized.add(trade.amount).subtract(trade.fee).subtract(costOut)
        }
        check(price > BigDecimal.ZERO)
    }
    return states.values.map { p ->
        val quote = quotes[p.asset.symbol]
        Position(p.asset,p.units,p.cost,p.realized,quote?.first,quote?.second,quote?.third)
    }.filter { it.units > BigDecimal.ZERO || it.realized != BigDecimal.ZERO }
}
