package cn.market.assistant

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Table creation and lightweight column migrations. Kept separate from [Ledger] so the data access
 * surface stays reviewable: schema changes never carry query or business logic.
 */
internal object LedgerSchema {
    /** Saved assistant recipes: declarative tool sequences, never executable code. */
    fun createRecipeSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS assistant_recipes (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '',
            steps TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_assistant_recipes_updated ON assistant_recipes(updated_at DESC)")
    }


fun createQuoteHistory(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS quote_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id TEXT NOT NULL, price TEXT NOT NULL,
        observed_at TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'MANUAL',
        UNIQUE(asset_id, observed_at))""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_quote_history_asset_time ON quote_history(asset_id,observed_at)")
}

fun createManualGoldQuoteSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS manual_gold_quotes (
        asset_id TEXT PRIMARY KEY, bank_buyback_price TEXT NOT NULL, bank_sell_price TEXT NOT NULL,
        observed_at TEXT NOT NULL, valid_until TEXT NOT NULL)""")
    db.execSQL("""CREATE TABLE IF NOT EXISTS manual_gold_quote_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id TEXT NOT NULL,
        bank_buyback_price TEXT NOT NULL, bank_sell_price TEXT NOT NULL,
        observed_at TEXT NOT NULL, valid_until TEXT NOT NULL,
        UNIQUE(asset_id,observed_at))""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_manual_gold_quote_history ON manual_gold_quote_history(asset_id,observed_at)")
}

fun createMarketSchema(db: SQLiteDatabase) {
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

fun createMarketBoardSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS market_boards (
        id TEXT PRIMARY KEY, board_type TEXT NOT NULL, name TEXT NOT NULL, price REAL NOT NULL,
        change REAL NOT NULL, change_pct REAL NOT NULL, turnover_amount REAL, turnover_rate_pct REAL,
        market_cap REAL, advancing_count INTEGER NOT NULL, declining_count INTEGER NOT NULL,
        quote_time INTEGER NOT NULL, fetched_at INTEGER NOT NULL, source TEXT NOT NULL)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_market_boards_type_name ON market_boards(board_type,name)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS market_board_snapshots (
        board_type TEXT PRIMARY KEY, expected_count INTEGER NOT NULL, fetched_at INTEGER NOT NULL,
        source TEXT NOT NULL)""")
}

fun createGoldHistorySchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS gold_daily_history (
        day TEXT NOT NULL, metric TEXT NOT NULL, value REAL NOT NULL,
        source TEXT NOT NULL, observed_at TEXT NOT NULL,
        PRIMARY KEY(day, metric))""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_gold_history_metric ON gold_daily_history(metric, day)")
}

fun createAlertSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS alert_rules (
        id TEXT PRIMARY KEY, kind TEXT NOT NULL, target_id TEXT, target_label TEXT,
        threshold REAL NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
        required_breaches INTEGER NOT NULL DEFAULT 1, cooldown_minutes INTEGER NOT NULL DEFAULT 60,
        quiet_start INTEGER, quiet_end INTEGER, last_fired_at INTEGER NOT NULL DEFAULT 0,
        trigger_count INTEGER NOT NULL DEFAULT 0, consecutive_breaches INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')""")
    db.execSQL("""CREATE TABLE IF NOT EXISTS alert_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT, rule_id TEXT NOT NULL, rule_kind TEXT NOT NULL,
        title TEXT NOT NULL, body TEXT NOT NULL, fired_at INTEGER NOT NULL, target_value REAL)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_alert_events_time ON alert_events(fired_at DESC)")
}

fun createMarketBoardMemberSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS market_board_members (
        board_id TEXT NOT NULL, secid TEXT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL,
        price REAL NOT NULL, change_pct REAL NOT NULL, turnover_rate_pct REAL, market_cap REAL,
        quote_time INTEGER NOT NULL, fetched_at INTEGER NOT NULL, source TEXT NOT NULL,
        PRIMARY KEY(board_id, secid))""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_board_members_change ON market_board_members(board_id,change_pct)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS market_board_member_snapshots (
        board_id TEXT PRIMARY KEY, board_name TEXT NOT NULL, total_count INTEGER NOT NULL,
        fetched_at INTEGER NOT NULL, source TEXT NOT NULL)""")
}

fun createDataSourceRunSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS data_source_runs (
        id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, dataset TEXT NOT NULL,
        status TEXT NOT NULL, requested_at INTEGER NOT NULL, item_count INTEGER NOT NULL,
        expected_count INTEGER, error_code TEXT)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_source_runs_time ON data_source_runs(requested_at DESC,id DESC)")
}

fun createFundNavSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS fund_nav_points (
        fund_code TEXT NOT NULL, nav_date TEXT NOT NULL, unit_nav REAL NOT NULL,
        accumulated_nav REAL, change_pct REAL, cached_at INTEGER NOT NULL,
        PRIMARY KEY(fund_code,nav_date))""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_fund_nav_date ON fund_nav_points(fund_code,nav_date)")
}

fun createFundNavResidualSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS fund_nav_residuals (
        fund_code TEXT NOT NULL, route_key TEXT NOT NULL, observation_day TEXT NOT NULL,
        residual_pct REAL NOT NULL, model_abs_error_pct REAL NOT NULL,
        baseline_abs_error_pct REAL NOT NULL, stored_at INTEGER NOT NULL,
        PRIMARY KEY(fund_code,route_key,observation_day))""")
}

fun createMarketChartSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS market_chart_bars (
        secid TEXT NOT NULL, period TEXT NOT NULL, trade_date TEXT NOT NULL,
        open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL DEFAULT 0,
        cached_at INTEGER NOT NULL, source TEXT NOT NULL DEFAULT '', PRIMARY KEY(secid,period,trade_date))""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_market_chart_time ON market_chart_bars(secid,period,trade_date)")
}

fun createPortfolioSnapshotSchema(db: SQLiteDatabase) {
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

fun addAccountValueSnapshotColumns(db: SQLiteDatabase) {
    if (!hasColumn(db, "portfolio_snapshots", "cash_balance")) db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN cash_balance TEXT")
    if (!hasColumn(db, "portfolio_snapshots", "total_value")) db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN total_value TEXT")
    if (!hasColumn(db, "portfolio_snapshots", "external_net_flow")) db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN external_net_flow TEXT")
}

fun addTwrBoundaryColumns(db: SQLiteDatabase) {
    if (!hasColumn(db, "portfolio_snapshots", "valuation_fresh")) {
        db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN valuation_fresh INTEGER NOT NULL DEFAULT 0")
    }
    if (!hasColumn(db, "account_cash_events", "occurred_at")) db.execSQL("ALTER TABLE account_cash_events ADD COLUMN occurred_at TEXT")
    if (!hasColumn(db, "account_cash_events", "portfolio_value_before")) db.execSQL("ALTER TABLE account_cash_events ADD COLUMN portfolio_value_before TEXT")
    if (!hasColumn(db, "account_cash_events", "valuation_complete")) {
        db.execSQL("ALTER TABLE account_cash_events ADD COLUMN valuation_complete INTEGER NOT NULL DEFAULT 0")
    }
}

fun addReversalAndSnapshotValidityColumns(db: SQLiteDatabase) {
    if (!hasColumn(db, "transactions", "reversed_at")) db.execSQL("ALTER TABLE transactions ADD COLUMN reversed_at TEXT")
    if (!hasColumn(db, "transactions", "reversal_reason")) db.execSQL("ALTER TABLE transactions ADD COLUMN reversal_reason TEXT")
    if (!hasColumn(db, "portfolio_snapshots", "valid")) {
        db.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN valid INTEGER NOT NULL DEFAULT 1")
    }
}

fun createAccountCashSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS account_cash_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, type TEXT NOT NULL,
        amount TEXT NOT NULL, memo TEXT NOT NULL DEFAULT '', transaction_id INTEGER UNIQUE,
        occurred_at TEXT, portfolio_value_before TEXT, valuation_complete INTEGER NOT NULL DEFAULT 0)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_account_cash_day ON account_cash_events(day,id)")
}

fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
    db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) return@use true
        false
    }
}
