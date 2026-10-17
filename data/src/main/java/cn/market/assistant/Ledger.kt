package cn.market.assistant

import cn.market.assistant.backup.EncryptedBackup
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.DataFetchStatus
import cn.market.assistant.domain.market.DataSourceRun
import cn.market.assistant.domain.market.DailyHistoryCoverage
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.domain.portfolio.AssetInfo
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.AccountCashEvent
import cn.market.assistant.domain.portfolio.AccountCashEventType
import cn.market.assistant.domain.portfolio.AccountCashLedger
import cn.market.assistant.domain.market.MarketBoard
import cn.market.assistant.domain.market.MarketBoardSnapshot
import cn.market.assistant.domain.market.MarketBoardMember
import cn.market.assistant.domain.market.MarketBoardMemberSnapshot
import cn.market.assistant.domain.assistant.AssistantMemoryStore
import cn.market.assistant.domain.assistant.AssistantSkill
import cn.market.assistant.domain.assistant.AssistantSkillStore
import cn.market.assistant.domain.assistant.AssistantWorkflow
import cn.market.assistant.domain.assistant.AssistantWorkflowStore
import cn.market.assistant.domain.assistant.MemoryEvent
import cn.market.assistant.domain.assistant.MemoryKind
import cn.market.assistant.domain.assistant.WorkflowCodec
import cn.market.assistant.domain.assistant.WorkflowStatus
import cn.market.assistant.domain.alert.AlertEvent
import cn.market.assistant.domain.alert.AlertRule
import cn.market.assistant.domain.alert.AlertRuleKind
import cn.market.assistant.domain.alert.AlertRuleStore
import cn.market.assistant.domain.market.MarketBoardType
import cn.market.assistant.domain.portfolio.AccountCashRepository
import cn.market.assistant.domain.portfolio.PortfolioAnalytics
import cn.market.assistant.domain.portfolio.PortfolioQuoteRouter
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.ManualGoldQuote
import cn.market.assistant.domain.portfolio.PositionSnapshot
import cn.market.assistant.domain.portfolio.Trade
import cn.market.assistant.domain.portfolio.TradeSide
import cn.market.assistant.domain.fund.FundNavHistoryCache
import cn.market.assistant.domain.fund.FundNavPoint
import cn.market.assistant.domain.fund.FundNavResidualSample

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class MarketBar(val day: String, val close: Double)

class Ledger(context: Context) : AccountCashRepository, AssistantSkillStore, AssistantMemoryStore,
    AssistantWorkflowStore, AlertRuleStore {
    private val appContext = context.applicationContext
    private val helper = LedgerOpenHelper(
        appContext,
        LedgerDatabaseEncryption.prepareAndGetPassphrase(appContext)
    )
    private val writableDatabase get() = helper.writableDatabase
    private val readableDatabase get() = helper.readableDatabase

    init {
        // Opening and schema upgrades happen on the caller's thread; app entry points use IO.
        LedgerDatabaseEncryption.withInitializationLock { helper.writableDatabase }
    }

    fun close() = helper.close()

    private inner class LedgerOpenHelper(context: Context, passphrase: ByteArray) : SQLiteOpenHelper(
        context,
        LedgerDatabaseEncryption.DATABASE_NAME,
        passphrase,
        null,
        24,
        0,
        LedgerDatabaseEncryption.errorHandler(),
        null,
        false
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            createSchema(db)
            seedCatalog(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            upgradeSchema(db, oldVersion, newVersion)
        }
    }

    private fun upgradeSchema(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
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
        if (oldVersion < 12) createMarketBoardSchema(db)
        if (oldVersion < 13 && !hasColumn(db, "transactions", "memo")) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN memo TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 14 && !hasColumn(db, "transactions", "share_factor")) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN share_factor TEXT")
        }
        if (oldVersion < 15) {
            if (!hasColumn(db, "transactions", "related_asset_id")) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN related_asset_id TEXT")
            }
            if (!hasColumn(db, "transactions", "related_units")) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN related_units TEXT")
            }
        }
        if (oldVersion < 16) createDataSourceRunSchema(db)
        if (oldVersion < 17) createFundNavSchema(db)
        if (oldVersion < 18) createFundNavResidualSchema(db)
        if (oldVersion < 19) {
            if (!hasColumn(db, "assets", "market_quote_secid")) {
                db.execSQL("ALTER TABLE assets ADD COLUMN market_quote_secid TEXT")
            }
            db.execSQL("UPDATE assets SET market_quote_secid='GOLD.SGE.AU9999' WHERE id='GOLD.SGE.AU9999' AND kind='GOLD'")
            db.execSQL("UPDATE assets SET market_quote_secid='1.518880' WHERE id='518880' AND kind='FUND'")
        }
        if (oldVersion < 20) createManualGoldQuoteSchema(db)
        if (oldVersion < 21 && !hasColumn(db, "market_chart_bars", "source")) {
            db.execSQL("ALTER TABLE market_chart_bars ADD COLUMN source TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 22) createMarketBoardMemberSchema(db)
        if (oldVersion < 23) {
            createAssistantSchema(db)
            seedAssistantSkills(db)
        }
        if (oldVersion < 24) createAlertSchema(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS assets (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, category TEXT NOT NULL,
            market_quote_secid TEXT)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id TEXT NOT NULL, side TEXT NOT NULL,
            day TEXT NOT NULL, amount TEXT NOT NULL, units TEXT, unit_price TEXT, fee TEXT NOT NULL,
            status TEXT NOT NULL, reversed_at TEXT, reversal_reason TEXT, memo TEXT NOT NULL DEFAULT '',
            share_factor TEXT, related_asset_id TEXT, related_units TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_asset_day ON transactions(asset_id,day)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS quotes (
            asset_id TEXT PRIMARY KEY, price TEXT NOT NULL, observed_at TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'MANUAL')""")
        createQuoteHistory(db)
        createManualGoldQuoteSchema(db)
        createMarketSchema(db)
        createMarketBoardSchema(db)
        createMarketBoardMemberSchema(db)
        createMarketChartSchema(db)
        createDataSourceRunSchema(db)
        createFundNavSchema(db)
        createFundNavResidualSchema(db)
        createPortfolioSnapshotSchema(db)
        createAccountCashSchema(db)
        createAssistantSchema(db)
        createAlertSchema(db)
        seedWatchlist(db)
        seedAssistantSkills(db)
    }

    private fun createQuoteHistory(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS quote_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id TEXT NOT NULL, price TEXT NOT NULL,
            observed_at TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'MANUAL',
            UNIQUE(asset_id, observed_at))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_quote_history_asset_time ON quote_history(asset_id,observed_at)")
    }

    private fun createManualGoldQuoteSchema(db: SQLiteDatabase) {
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

    private fun createMarketBoardSchema(db: SQLiteDatabase) {
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

    private fun createAssistantSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS assistant_skills (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, instructions TEXT NOT NULL,
            tool_ids TEXT NOT NULL DEFAULT '', trigger_words TEXT NOT NULL DEFAULT '',
            enabled INTEGER NOT NULL DEFAULT 1, usage_count INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS assistant_memory_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, key TEXT NOT NULL,
            at_millis INTEGER NOT NULL, weight REAL NOT NULL DEFAULT 1)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_assistant_memory_kind ON assistant_memory_events(kind,at_millis DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS assistant_workflows (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, status TEXT NOT NULL, turns TEXT NOT NULL,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_assistant_workflows_updated ON assistant_workflows(updated_at DESC)")
    }

    private fun seedAssistantSkills(db: SQLiteDatabase) {
        val count = db.rawQuery("SELECT COUNT(*) FROM assistant_skills", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (count > 0) return
        val now = System.currentTimeMillis()
        val seeds = listOf(
            AssistantSkill(
                id = "weekly-review",
                name = "周报复盘",
                description = "每周收益、回撤与配置变化",
                instructions = "1. 先用 portfolio.period_report 取本周事实（盈亏、TWR、最大回撤、贡献）。\n" +
                    "2. 再对比基准与上周配置变化。\n" +
                    "3. 最后单列数据缺口与不确定项，不给操作建议。",
                toolIds = listOf("portfolio.period_report", "market.quote"),
                triggerWords = listOf("周报", "本周", "复盘", "这周"),
                createdAtMillis = now,
                updatedAtMillis = now
            ),
            AssistantSkill(
                id = "valuation-check",
                name = "估值解读",
                description = "解读基金盘中估算、误差区间与降级原因",
                instructions = "解释估值方法与样本外误差，说明何时不给数字；把估算明确标为「估算，非官方净值」，" +
                    "不预测涨跌。",
                toolIds = listOf("market.quote", "web.fetch"),
                triggerWords = listOf("估值", "估算", "盘中"),
                createdAtMillis = now,
                updatedAtMillis = now
            ),
            AssistantSkill(
                id = "risk-review",
                name = "风险体检",
                description = "持仓集中度、相关性与尾部风险",
                instructions = "读取本地持仓与快照，列出集中度、前几大持仓与相关性；样本不足时明确说需要多少交易日，" +
                    "不把风险统计说成预测。",
                toolIds = listOf("portfolio.positions", "portfolio.period_report"),
                triggerWords = listOf("风险", "回撤", "集中度", "相关性"),
                createdAtMillis = now,
                updatedAtMillis = now
            ),
            AssistantSkill(
                id = "gold-watch",
                name = "黄金跟踪",
                description = "国内外金价、溢价与持仓折算",
                instructions = "用 web.fetch 或 market.quote 取金价，列出币种/单位/来源/时点；" +
                    "银行报价只能引用用户录入或标定值，不得用国际金价反推。",
                toolIds = listOf("market.quote", "web.fetch", "portfolio.positions"),
                triggerWords = listOf("黄金", "金价", "积存金"),
                createdAtMillis = now,
                updatedAtMillis = now
            )
        )
        seeds.forEach { skill ->
            db.insertWithOnConflict("assistant_skills", null, assistantSkillValues(skill), SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun assistantSkillValues(skill: AssistantSkill) = ContentValues().apply {
        put("id", skill.id)
        put("name", skill.name)
        put("description", skill.description)
        put("instructions", skill.instructions)
        put("tool_ids", skill.toolIds.joinToString(","))
        put("trigger_words", skill.triggerWords.joinToString(","))
        put("enabled", if (skill.enabled) 1 else 0)
        put("usage_count", skill.usageCount)
        put("created_at", skill.createdAtMillis)
        put("updated_at", skill.updatedAtMillis)
    }

    override suspend fun skills(): List<AssistantSkill> = buildList {
        readableDatabase.rawQuery(
            """SELECT id,name,description,instructions,tool_ids,trigger_words,enabled,usage_count,
                created_at,updated_at FROM assistant_skills ORDER BY updated_at DESC""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) add(
                AssistantSkill(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    description = cursor.getString(2),
                    instructions = cursor.getString(3),
                    toolIds = cursor.getString(4).split(',').map { it.trim() }.filter { it.isNotBlank() },
                    triggerWords = cursor.getString(5).split(',').map { it.trim() }.filter { it.isNotBlank() },
                    enabled = cursor.getInt(6) == 1,
                    usageCount = cursor.getInt(7),
                    createdAtMillis = cursor.getLong(8),
                    updatedAtMillis = cursor.getLong(9)
                )
            )
        }
    }

    override suspend fun upsertSkill(skill: AssistantSkill): AssistantSkill {
        require(skill.id.isNotBlank() && skill.id.matches(Regex("[a-z][a-z0-9_-]{1,48}"))) { "技能 ID 需为小写字母开头" }
        require(skill.name.isNotBlank()) { "技能名称不能为空" }
        val stored = skill.copy(updatedAtMillis = System.currentTimeMillis())
        writableDatabase.insertWithOnConflict(
            "assistant_skills", null, assistantSkillValues(stored), SQLiteDatabase.CONFLICT_REPLACE
        )
        return stored
    }

    override suspend fun deleteSkill(id: String) {
        writableDatabase.delete("assistant_skills", "id=?", arrayOf(id))
    }

    override suspend fun recordSkillUse(id: String, atMillis: Long) {
        writableDatabase.execSQL(
            "UPDATE assistant_skills SET usage_count=usage_count+1, updated_at=? WHERE id=?",
            arrayOf<Any>(atMillis, id)
        )
    }

    override suspend fun recordMemory(event: MemoryEvent) {
        if (event.key.isBlank()) return
        writableDatabase.insert("assistant_memory_events", null, ContentValues().apply {
            put("kind", event.kind.name)
            put("key", event.key.take(200))
            put("at_millis", event.atMillis)
            put("weight", event.weight)
        })
    }

    override suspend fun memoryEvents(limit: Int): List<MemoryEvent> = buildList {
        readableDatabase.rawQuery(
            "SELECT kind,key,at_millis,weight FROM assistant_memory_events ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 5_000).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = runCatching { MemoryKind.valueOf(cursor.getString(0)) }.getOrNull() ?: continue
                add(MemoryEvent(kind, cursor.getString(1), cursor.getLong(2), cursor.getDouble(3)))
            }
        }
    }

    override suspend fun clearMemory() {
        writableDatabase.delete("assistant_memory_events", null, null)
    }

    override suspend fun workflows(limit: Int): List<AssistantWorkflow> = buildList {
        readableDatabase.rawQuery(
            "SELECT id,title,status,turns,created_at,updated_at FROM assistant_workflows " +
                "ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 200).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) add(readWorkflow(cursor))
        }
    }

    override suspend fun workflow(id: String): AssistantWorkflow? =
        readableDatabase.rawQuery(
            "SELECT id,title,status,turns,created_at,updated_at FROM assistant_workflows WHERE id=?",
            arrayOf(id)
        ).use { cursor -> if (cursor.moveToFirst()) readWorkflow(cursor) else null }

    override suspend fun upsertWorkflow(workflow: AssistantWorkflow) {
        require(workflow.id.isNotBlank()) { "工作流 ID 不能为空" }
        val stored = workflow.copy(
            title = workflow.title.take(120).ifBlank { "未命名会话" },
            updatedAtMillis = System.currentTimeMillis()
        )
        writableDatabase.insertWithOnConflict("assistant_workflows", null, ContentValues().apply {
            put("id", stored.id)
            put("title", stored.title)
            put("status", stored.status.name)
            put("turns", WorkflowCodec.encode(stored.turns))
            put("created_at", stored.createdAtMillis)
            put("updated_at", stored.updatedAtMillis)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override suspend fun deleteWorkflow(id: String) {
        writableDatabase.delete("assistant_workflows", "id=?", arrayOf(id))
    }

    override suspend fun clearWorkflows() {
        writableDatabase.delete("assistant_workflows", null, null)
    }

    private fun readWorkflow(cursor: android.database.Cursor): AssistantWorkflow = AssistantWorkflow(
        id = cursor.getString(0),
        title = cursor.getString(1),
        status = runCatching { WorkflowStatus.valueOf(cursor.getString(2)) }.getOrDefault(WorkflowStatus.ACTIVE),
        turns = WorkflowCodec.decode(cursor.getString(3)),
        createdAtMillis = cursor.getLong(4),
        updatedAtMillis = cursor.getLong(5)
    )

    private fun createAlertSchema(db: SQLiteDatabase) {
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

    override suspend fun alertRules(): List<AlertRule> = buildList {
        readableDatabase.rawQuery(
            """SELECT id,kind,target_id,target_label,threshold,enabled,required_breaches,cooldown_minutes,
                quiet_start,quiet_end,last_fired_at,trigger_count,consecutive_breaches,created_at,updated_at,note
                FROM alert_rules ORDER BY updated_at DESC""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = runCatching { AlertRuleKind.valueOf(cursor.getString(1)) }.getOrNull() ?: continue
                add(
                    AlertRule(
                        id = cursor.getString(0),
                        kind = kind,
                        targetId = cursor.getString(2),
                        targetLabel = cursor.getString(3),
                        threshold = cursor.getDouble(4),
                        enabled = cursor.getInt(5) == 1,
                        requiredBreaches = cursor.getInt(6),
                        cooldownMinutes = cursor.getInt(7),
                        quietStartHour = if (cursor.isNull(8)) null else cursor.getInt(8),
                        quietEndHour = if (cursor.isNull(9)) null else cursor.getInt(9),
                        lastFiredAtMillis = cursor.getLong(10),
                        triggerCount = cursor.getInt(11),
                        consecutiveBreaches = cursor.getInt(12),
                        createdAtMillis = cursor.getLong(13),
                        updatedAtMillis = cursor.getLong(14),
                        note = cursor.getString(15)
                    )
                )
            }
        }
    }

    override suspend fun upsertAlertRule(rule: AlertRule) {
        require(rule.id.isNotBlank()) { "提醒规则 ID 不能为空" }
        require(rule.threshold.isFinite()) { "提醒阈值必须是有限数" }
        writableDatabase.insertWithOnConflict("alert_rules", null, ContentValues().apply {
            put("id", rule.id)
            put("kind", rule.kind.name)
            rule.targetId?.let { put("target_id", it) } ?: putNull("target_id")
            rule.targetLabel?.let { put("target_label", it) } ?: putNull("target_label")
            put("threshold", rule.threshold)
            put("enabled", if (rule.enabled) 1 else 0)
            put("required_breaches", rule.requiredBreaches.coerceIn(1, 10))
            put("cooldown_minutes", rule.cooldownMinutes.coerceIn(0, 10_080))
            rule.quietStartHour?.let { put("quiet_start", it.coerceIn(0, 23)) } ?: putNull("quiet_start")
            rule.quietEndHour?.let { put("quiet_end", it.coerceIn(0, 23)) } ?: putNull("quiet_end")
            put("last_fired_at", rule.lastFiredAtMillis)
            put("trigger_count", rule.triggerCount)
            put("consecutive_breaches", rule.consecutiveBreaches)
            put("created_at", rule.createdAtMillis)
            put("updated_at", System.currentTimeMillis())
            put("note", rule.note.take(200))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override suspend fun deleteAlertRule(id: String) {
        writableDatabase.delete("alert_rules", "id=?", arrayOf(id))
    }

    override suspend fun alertEvents(limit: Int): List<AlertEvent> = buildList {
        readableDatabase.rawQuery(
            "SELECT id,rule_id,rule_kind,title,body,fired_at,target_value FROM alert_events " +
                "ORDER BY fired_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = runCatching { AlertRuleKind.valueOf(cursor.getString(2)) }.getOrNull() ?: continue
                add(
                    AlertEvent(
                        id = cursor.getLong(0),
                        ruleId = cursor.getString(1),
                        ruleKind = kind,
                        title = cursor.getString(3),
                        body = cursor.getString(4),
                        firedAtMillis = cursor.getLong(5),
                        targetValue = if (cursor.isNull(6)) null else cursor.getDouble(6)
                    )
                )
            }
        }
    }

    override suspend fun recordAlertEvent(event: AlertEvent) {
        writableDatabase.insert("alert_events", null, ContentValues().apply {
            put("rule_id", event.ruleId)
            put("rule_kind", event.ruleKind.name)
            put("title", event.title)
            put("body", event.body)
            put("fired_at", event.firedAtMillis)
            event.targetValue?.let { put("target_value", it) } ?: putNull("target_value")
        })
    }

    override suspend fun clearAlertEvents() {
        writableDatabase.delete("alert_events", null, null)
    }

    private fun createMarketBoardMemberSchema(db: SQLiteDatabase) {
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

    private fun createDataSourceRunSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS data_source_runs (
            id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, dataset TEXT NOT NULL,
            status TEXT NOT NULL, requested_at INTEGER NOT NULL, item_count INTEGER NOT NULL,
            expected_count INTEGER, error_code TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_source_runs_time ON data_source_runs(requested_at DESC,id DESC)")
    }

    private fun createFundNavSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS fund_nav_points (
            fund_code TEXT NOT NULL, nav_date TEXT NOT NULL, unit_nav REAL NOT NULL,
            accumulated_nav REAL, change_pct REAL, cached_at INTEGER NOT NULL,
            PRIMARY KEY(fund_code,nav_date))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fund_nav_date ON fund_nav_points(fund_code,nav_date)")
    }

    private fun createFundNavResidualSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS fund_nav_residuals (
            fund_code TEXT NOT NULL, route_key TEXT NOT NULL, observation_day TEXT NOT NULL,
            residual_pct REAL NOT NULL, model_abs_error_pct REAL NOT NULL,
            baseline_abs_error_pct REAL NOT NULL, stored_at INTEGER NOT NULL,
            PRIMARY KEY(fund_code,route_key,observation_day))""")
    }

    private fun createMarketChartSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS market_chart_bars (
            secid TEXT NOT NULL, period TEXT NOT NULL, trade_date TEXT NOT NULL,
            open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL DEFAULT 0,
            cached_at INTEGER NOT NULL, source TEXT NOT NULL DEFAULT '', PRIMARY KEY(secid,period,trade_date))""")
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

    private fun canonicalDailyTradeDate(value: String): String = runCatching {
        val trimmed = value.trim()
        val date = if (trimmed.length == 8 && trimmed.all(Char::isDigit)) {
            java.time.LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        } else {
            java.time.LocalDate.parse(trimmed.take(10))
        }
        date.toString()
    }.getOrDefault(value)

    private fun quotesFreshAt(positions: List<Position>, at: Instant): Boolean = positions.all { position ->
        if (position.units.signum() == 0) return@all true
        if (position.quote == null) return false
        val quoteTime = position.quoteTime?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        val timestampFresh = !quoteTime.isAfter(at.plusSeconds(5 * 60)) &&
            java.time.Duration.between(quoteTime, at) <= java.time.Duration.ofHours(72)
        if (!timestampFresh) return false
        if (position.quoteSource == "MANUAL_BANK_BUYBACK") {
            val validUntil = position.manualGoldQuote?.validUntil
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
            return@all at.isBefore(validUntil)
        }
        true
    }

    fun chartBars(secid: String, period: ChartPeriod): List<MarketOhlcBar> = buildList {
        readableDatabase.rawQuery(
            "SELECT trade_date,open,close,high,low,volume FROM market_chart_bars " +
                "WHERE secid=? AND period=? ORDER BY " +
                "CASE WHEN LENGTH(trade_date)=8 THEN " +
                "SUBSTR(trade_date,1,4)||'-'||SUBSTR(trade_date,5,2)||'-'||SUBSTR(trade_date,7,2) " +
                "ELSE SUBSTR(trade_date,1,10) END, cached_at DESC",
            arrayOf(secid, period.cacheKey)).use { c ->
            while (c.moveToNext()) {
                val rawDay = c.getString(0)
                val day = if (period == ChartPeriod.DAY) canonicalDailyTradeDate(rawDay) else rawDay
                add(MarketOhlcBar(day, c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)))
            }
        }
    }.distinctBy { it.day }.sortedBy { it.day }

    fun chartBarsCachedAt(secid: String, period: ChartPeriod): Long = readableDatabase.rawQuery(
        "SELECT MAX(cached_at) FROM market_chart_bars WHERE secid=? AND period=?", arrayOf(secid, period.cacheKey)
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun chartBarsSource(secid: String, period: ChartPeriod): String? = readableDatabase.rawQuery(
        "SELECT source FROM market_chart_bars WHERE secid=? AND period=? AND source!='' " +
            "ORDER BY cached_at DESC,trade_date DESC LIMIT 1",
        arrayOf(secid, period.cacheKey)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun dailyHistoryCoverage(): List<DailyHistoryCoverage> = buildList {
        readableDatabase.rawQuery(
            "SELECT secid,COUNT(*),MIN(trade_date),MAX(trade_date),MAX(cached_at) " +
                "FROM market_chart_bars WHERE period=? GROUP BY secid ORDER BY MAX(cached_at) DESC",
            arrayOf(ChartPeriod.DAY.cacheKey)
        ).use { cursor ->
            while (cursor.moveToNext()) add(DailyHistoryCoverage(
                cursor.getString(0), cursor.getInt(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4)
            ))
        }
    }

    fun dailyChartDaysByInstrument(): Map<String, List<String>> {
        val daysByInstrument = linkedMapOf<String, MutableList<String>>()
        readableDatabase.rawQuery(
            "SELECT secid,trade_date FROM market_chart_bars WHERE period=? ORDER BY secid,trade_date",
            arrayOf(ChartPeriod.DAY.cacheKey)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val secid = cursor.getString(0)
                val day = cursor.getString(1)
                daysByInstrument.getOrPut(secid) { mutableListOf() }.add(day)
            }
        }
        return daysByInstrument.mapValues { (_, days) -> days.toList() }
    }

    fun saveChartBars(
        secid: String,
        period: ChartPeriod,
        bars: List<MarketOhlcBar>,
        cachedAt: Long = System.currentTimeMillis(),
        source: String = ""
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            bars.forEach { bar -> db.insertWithOnConflict("market_chart_bars", null, ContentValues().apply {
                put("secid", secid); put("period", period.cacheKey)
                put("trade_date", if (period == ChartPeriod.DAY) canonicalDailyTradeDate(bar.day) else bar.day)
                put("open", bar.open); put("high", bar.high); put("low", bar.low); put("close", bar.close); put("volume", bar.volume); put("cached_at", cachedAt); put("source", source)
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

    fun fundWatchlist(): List<AssetInfo> {
        val watchedCodes = watchlist().toSet()
        return assets(AssetKind.FUND).filter { it.symbol in watchedCodes && it.symbol.matches(Regex("\\d{6}")) }
    }

    fun isFundWatched(code: String): Boolean = watchlist().contains(code.trim().uppercase())

    fun setFundWatched(code: String, name: String, watched: Boolean, category: String = "公募基金") {
        val normalizedCode = code.trim().uppercase()
        require(normalizedCode.matches(Regex("\\d{6}"))) { "基金代码格式无效" }
        if (watched) {
            addFundAsset(normalizedCode, name, category)
            writableDatabase.insertWithOnConflict("watchlist", null, ContentValues().apply {
                put("secid", normalizedCode); put("added_at", Instant.now().toString())
            }, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            writableDatabase.delete("watchlist", "secid=?", arrayOf(normalizedCode))
        }
    }

    fun saveFundNavHistory(
        code: String,
        points: List<FundNavPoint>,
        cachedAt: Long = System.currentTimeMillis() / 1000
    ): Int {
        val normalizedCode = code.trim().uppercase()
        require(normalizedCode.matches(Regex("\\d{6}"))) { "基金代码格式无效" }
        val uniquePoints = points.distinctBy { it.day }
        require(uniquePoints.all { point ->
            point.day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                point.unitNav.isFinite() && point.unitNav > 0 &&
                point.accumulatedNav?.isFinite() != false && point.changePct?.isFinite() != false
        }) { "基金净值数据无效" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            uniquePoints.forEach { point ->
                db.insertWithOnConflict("fund_nav_points", null, ContentValues().apply {
                    put("fund_code", normalizedCode)
                    put("nav_date", point.day)
                    put("unit_nav", point.unitNav)
                    put("accumulated_nav", point.accumulatedNav)
                    put("change_pct", point.changePct)
                    put("cached_at", cachedAt)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return uniquePoints.size
    }

    fun fundNavHistory(code: String, limit: Int? = null): FundNavHistoryCache {
        val normalizedCode = code.trim().uppercase()
        require(normalizedCode.matches(Regex("\\d{6}"))) { "基金代码格式无效" }
        val db = readableDatabase
        var totalCount = 0
        var firstDay: String? = null
        var lastDay: String? = null
        var cachedAt: Long? = null
        db.rawQuery(
            "SELECT COUNT(*),MIN(nav_date),MAX(nav_date),MAX(cached_at) FROM fund_nav_points WHERE fund_code=?",
            arrayOf(normalizedCode)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                totalCount = cursor.getInt(0)
                firstDay = cursor.getString(1)
                lastDay = cursor.getString(2)
                if (!cursor.isNull(3)) cachedAt = cursor.getLong(3)
            }
        }
        val points = mutableListOf<FundNavPoint>()
        val safeLimit = limit?.coerceIn(1, 100_000)
        val sql = if (safeLimit == null) {
            "SELECT nav_date,unit_nav,accumulated_nav,change_pct FROM fund_nav_points WHERE fund_code=? ORDER BY nav_date"
        } else {
            "SELECT nav_date,unit_nav,accumulated_nav,change_pct FROM fund_nav_points WHERE fund_code=? ORDER BY nav_date DESC LIMIT $safeLimit"
        }
        db.rawQuery(sql, arrayOf(normalizedCode)).use { cursor ->
            while (cursor.moveToNext()) points += FundNavPoint(
                day = cursor.getString(0),
                unitNav = cursor.getDouble(1),
                accumulatedNav = if (cursor.isNull(2)) null else cursor.getDouble(2),
                changePct = if (cursor.isNull(3)) null else cursor.getDouble(3)
            )
        }
        return FundNavHistoryCache(
            code = normalizedCode,
            points = if (safeLimit == null) points else points.asReversed(),
            totalCount = totalCount,
            firstDay = firstDay,
            lastDay = lastDay,
            cachedAt = cachedAt
        )
    }

    fun saveFundNavResidualSamples(samples: List<FundNavResidualSample>): Int {
        if (samples.isEmpty()) return 0
        val normalizedSamples = samples.distinctBy { Triple(it.fundCode.trim().uppercase(), it.routeKey, it.observationDay) }
        require(normalizedSamples.all { sample ->
            sample.fundCode.trim().uppercase().matches(Regex("\\d{6}")) &&
                sample.routeKey.matches(Regex("[A-Za-z0-9:._-]{1,128}")) &&
                runCatching { LocalDate.parse(sample.observationDay) }.isSuccess &&
                sample.residualPct.isFinite() && sample.modelAbsoluteErrorPct.isFinite() &&
                sample.modelAbsoluteErrorPct >= 0.0 && sample.zeroBaselineAbsoluteErrorPct.isFinite() &&
                sample.zeroBaselineAbsoluteErrorPct >= 0.0
        }) { "基金估值误差样本无效" }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val storedAt = System.currentTimeMillis() / 1000
            normalizedSamples.forEach { sample ->
                db.insertWithOnConflict("fund_nav_residuals", null, ContentValues().apply {
                    put("fund_code", sample.fundCode.trim().uppercase())
                    put("route_key", sample.routeKey)
                    put("observation_day", sample.observationDay)
                    put("residual_pct", sample.residualPct)
                    put("model_abs_error_pct", sample.modelAbsoluteErrorPct)
                    put("baseline_abs_error_pct", sample.zeroBaselineAbsoluteErrorPct)
                    put("stored_at", storedAt)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            normalizedSamples.map { it.fundCode.trim().uppercase() to it.routeKey }.distinct().forEach { (code, routeKey) ->
                db.execSQL(
                    """DELETE FROM fund_nav_residuals WHERE fund_code=? AND route_key=? AND observation_day NOT IN (
                        SELECT observation_day FROM fund_nav_residuals
                        WHERE fund_code=? AND route_key=? ORDER BY observation_day DESC LIMIT 500)""",
                    arrayOf(code, routeKey, code, routeKey)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return normalizedSamples.size
    }

    fun fundNavResidualSamples(code: String, routeKey: String, limit: Int = 500): List<FundNavResidualSample> {
        val normalizedCode = code.trim().uppercase()
        val normalizedRouteKey = routeKey.trim()
        require(normalizedCode.matches(Regex("\\d{6}"))) { "基金代码格式无效" }
        require(normalizedRouteKey.matches(Regex("[A-Za-z0-9:._-]{1,128}"))) { "基金估值路由无效" }
        val safeLimit = limit.coerceIn(1, 500)
        return buildList {
            readableDatabase.rawQuery(
                """SELECT fund_code,route_key,observation_day,residual_pct,model_abs_error_pct,baseline_abs_error_pct
                    FROM fund_nav_residuals WHERE fund_code=? AND route_key=?
                    ORDER BY observation_day DESC LIMIT ?""",
                arrayOf(normalizedCode, normalizedRouteKey, safeLimit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) add(
                    FundNavResidualSample(
                        fundCode = cursor.getString(0),
                        routeKey = cursor.getString(1),
                        observationDay = cursor.getString(2),
                        residualPct = cursor.getDouble(3),
                        modelAbsoluteErrorPct = cursor.getDouble(4),
                        zeroBaselineAbsoluteErrorPct = cursor.getDouble(5)
                    )
                )
            }
        }.asReversed()
    }

    fun marketQuotes(): Map<String, MarketQuote> = buildMap {
        readableDatabase.rawQuery("SELECT secid,name,price,change,change_pct,quote_time,fetched_at,source FROM market_quotes", null).use { c ->
            while (c.moveToNext()) put(c.getString(0), MarketQuote(c.getString(0), c.getString(1), c.getDouble(2),
                c.getDouble(3), c.getDouble(4), c.getLong(5), c.getLong(6), c.getString(7)))
        }
    }

    fun recordDataSourceRun(
        source: String,
        dataset: String,
        status: DataFetchStatus,
        itemCount: Int,
        expectedCount: Int? = null,
        errorCode: String? = null,
        requestedAt: Long = System.currentTimeMillis() / 1000
    ) {
        require(source.isNotBlank() && source.length <= 80) { "数据源名称无效" }
        require(dataset.isNotBlank() && dataset.length <= 80) { "数据集名称无效" }
        require(itemCount >= 0 && (expectedCount == null || expectedCount >= 0)) { "数据覆盖数量无效" }
        val safeErrorCode = errorCode?.filter { it.isLetterOrDigit() || it == '_' }?.take(48)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("data_source_runs", null, ContentValues().apply {
                put("source", source)
                put("dataset", dataset)
                put("status", status.name)
                put("requested_at", requestedAt)
                put("item_count", itemCount)
                put("expected_count", expectedCount)
                put("error_code", safeErrorCode)
            })
            db.execSQL("DELETE FROM data_source_runs WHERE id NOT IN (SELECT id FROM data_source_runs ORDER BY id DESC LIMIT 500)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun dataSourceRuns(limit: Int = 40): List<DataSourceRun> {
        require(limit in 1..200) { "数据源历史条数无效" }
        return buildList {
            readableDatabase.rawQuery(
                "SELECT id,source,dataset,status,requested_at,item_count,expected_count,error_code " +
                    "FROM data_source_runs ORDER BY requested_at DESC,id DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) add(DataSourceRun(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    DataFetchStatus.valueOf(cursor.getString(3)), cursor.getLong(4), cursor.getInt(5),
                    if (cursor.isNull(6)) null else cursor.getInt(6),
                    cursor.getString(7)
                ))
            }
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

    fun marketBoardSnapshot(type: MarketBoardType): MarketBoardSnapshot? {
        val metadata = readableDatabase.rawQuery(
            "SELECT expected_count,fetched_at,source FROM market_board_snapshots WHERE board_type=?",
            arrayOf(type.name)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Triple(cursor.getInt(0), cursor.getLong(1), cursor.getString(2))
        }
        val boards = buildList {
            readableDatabase.rawQuery(
                """SELECT id,name,price,change,change_pct,turnover_amount,turnover_rate_pct,market_cap,
                    advancing_count,declining_count,quote_time,fetched_at,source
                    FROM market_boards WHERE board_type=? ORDER BY change_pct DESC""",
                arrayOf(type.name)
            ).use { cursor ->
                while (cursor.moveToNext()) add(MarketBoard(
                    id = cursor.getString(0),
                    type = type,
                    name = cursor.getString(1),
                    price = cursor.getDouble(2),
                    change = cursor.getDouble(3),
                    changePct = cursor.getDouble(4),
                    turnoverAmount = if (cursor.isNull(5)) null else cursor.getDouble(5),
                    turnoverRatePct = if (cursor.isNull(6)) null else cursor.getDouble(6),
                    marketCap = if (cursor.isNull(7)) null else cursor.getDouble(7),
                    advancingCount = cursor.getInt(8),
                    decliningCount = cursor.getInt(9),
                    quoteTime = cursor.getLong(10),
                    fetchedAt = cursor.getLong(11),
                    source = cursor.getString(12)
                ))
            }
        }
        return MarketBoardSnapshot(type, boards, metadata.first, metadata.second, metadata.third)
    }

    fun saveMarketBoardSnapshot(snapshot: MarketBoardSnapshot) {
        require(snapshot.isComplete) { "不完整的板块列表不能覆盖本地缓存" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("market_boards", "board_type=?", arrayOf(snapshot.type.name))
            snapshot.boards.forEach { board ->
                db.insertOrThrow("market_boards", null, ContentValues().apply {
                    put("id", board.id)
                    put("board_type", board.type.name)
                    put("name", board.name)
                    put("price", board.price)
                    put("change", board.change)
                    put("change_pct", board.changePct)
                    board.turnoverAmount?.let { put("turnover_amount", it) } ?: putNull("turnover_amount")
                    board.turnoverRatePct?.let { put("turnover_rate_pct", it) } ?: putNull("turnover_rate_pct")
                    board.marketCap?.let { put("market_cap", it) } ?: putNull("market_cap")
                    put("advancing_count", board.advancingCount)
                    put("declining_count", board.decliningCount)
                    put("quote_time", board.quoteTime)
                    put("fetched_at", board.fetchedAt)
                    put("source", board.source)
                })
            }
            db.insertWithOnConflict("market_board_snapshots", null, ContentValues().apply {
                put("board_type", snapshot.type.name)
                put("expected_count", snapshot.expectedCount)
                put("fetched_at", snapshot.fetchedAt)
                put("source", snapshot.source)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun marketBoardMemberSnapshot(boardId: String): MarketBoardMemberSnapshot? {
        val metadata = readableDatabase.rawQuery(
            "SELECT board_name,total_count,fetched_at,source FROM market_board_member_snapshots WHERE board_id=?",
            arrayOf(boardId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            listOf(cursor.getString(0), cursor.getInt(1), cursor.getLong(2), cursor.getString(3))
        }
        val members = buildList {
            readableDatabase.rawQuery(
                """SELECT secid,code,name,price,change_pct,turnover_rate_pct,market_cap,quote_time,fetched_at,source
                    FROM market_board_members WHERE board_id=? ORDER BY market_cap DESC""",
                arrayOf(boardId)
            ).use { cursor ->
                while (cursor.moveToNext()) add(
                    MarketBoardMember(
                        secid = cursor.getString(0),
                        code = cursor.getString(1),
                        name = cursor.getString(2),
                        price = cursor.getDouble(3),
                        changePct = cursor.getDouble(4),
                        turnoverRatePct = if (cursor.isNull(5)) null else cursor.getDouble(5),
                        marketCap = if (cursor.isNull(6)) null else cursor.getDouble(6),
                        quoteTime = cursor.getLong(7),
                        fetchedAt = cursor.getLong(8),
                        source = cursor.getString(9)
                    )
                )
            }
        }
        return MarketBoardMemberSnapshot(
            boardId = boardId,
            boardName = metadata[0] as String,
            members = members,
            totalCount = metadata[1] as Int,
            fetchedAt = metadata[2] as Long,
            source = metadata[3] as String
        )
    }

    fun saveMarketBoardMemberSnapshot(snapshot: MarketBoardMemberSnapshot) {
        require(snapshot.members.isNotEmpty()) { "空的板块成分不能覆盖本地缓存" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("market_board_members", "board_id=?", arrayOf(snapshot.boardId))
            snapshot.members.forEach { member ->
                db.insertOrThrow("market_board_members", null, ContentValues().apply {
                    put("board_id", snapshot.boardId)
                    put("secid", member.secid)
                    put("code", member.code)
                    put("name", member.name)
                    put("price", member.price)
                    put("change_pct", member.changePct)
                    member.turnoverRatePct?.let { put("turnover_rate_pct", it) } ?: putNull("turnover_rate_pct")
                    member.marketCap?.let { put("market_cap", it) } ?: putNull("market_cap")
                    put("quote_time", member.quoteTime)
                    put("fetched_at", member.fetchedAt)
                    put("source", member.source)
                })
            }
            db.insertWithOnConflict("market_board_member_snapshots", null, ContentValues().apply {
                put("board_id", snapshot.boardId)
                put("board_name", snapshot.boardName)
                put("total_count", snapshot.totalCount)
                put("fetched_at", snapshot.fetchedAt)
                put("source", snapshot.source)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
                val asset = AssetInfo(
                    item.getString("symbol"), item.getString("name"),
                    AssetKind.valueOf(item.getString("kind")), item.getString("category")
                )
                val values = ContentValues().apply {
                    put("id", asset.symbol); put("name", asset.name)
                    put("kind", asset.kind.name); put("category", asset.category)
                    put("market_quote_secid", PortfolioQuoteRouter.defaultQuoteSecid(asset))
                }
                db.insertWithOnConflict("assets", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun assets(kind: AssetKind? = null): List<AssetInfo> {
        val result = mutableListOf<AssetInfo>()
        val sql = if (kind == null) "SELECT id,name,kind,category,market_quote_secid FROM assets ORDER BY kind,name"
            else "SELECT id,name,kind,category,market_quote_secid FROM assets WHERE kind=? ORDER BY name"
        readableDatabase.rawQuery(sql, if (kind == null) null else arrayOf(kind.name)).use { c ->
            while (c.moveToNext()) result += AssetInfo(
                c.getString(0), c.getString(1), AssetKind.valueOf(c.getString(2)), c.getString(3),
                c.getString(4)
            )
        }
        return result
    }

    fun findAsset(id: String): AssetInfo? = assets().firstOrNull { it.symbol == id }

    fun addCustomAsset(symbol: String, name: String, kind: AssetKind): AssetInfo {
        val base = AssetInfo(symbol.trim().uppercase(), name.trim(), kind, if (kind == AssetKind.FUND) "自定义基金" else "黄金")
        val asset = base.copy(marketQuoteSecid = PortfolioQuoteRouter.defaultQuoteSecid(base))
        require(asset.symbol.isNotBlank() && asset.name.isNotBlank()) { "请填写名称和代码" }
        writableDatabase.insertOrThrow("assets", null, ContentValues().apply {
            put("id",asset.symbol); put("name",asset.name); put("kind",asset.kind.name); put("category",asset.category)
            put("market_quote_secid", asset.marketQuoteSecid)
        })
        return asset
    }

    fun addFundAsset(code: String, name: String, category: String = "公募基金"): AssetInfo {
        val normalizedCode = code.trim().uppercase()
        val normalizedName = name.trim()
        require(normalizedCode.matches(Regex("\\d{6}")) && normalizedName.isNotBlank()) { "基金代码或名称无效" }
        val normalizedCategory = category.trim().ifBlank { "公募基金" }
        findAsset(normalizedCode)?.let { existing ->
            if (existing.category == "公募基金" && normalizedCategory != "公募基金") {
                writableDatabase.update(
                    "assets",
                    ContentValues().apply { put("category", normalizedCategory) },
                    "id=? AND kind=?",
                    arrayOf(normalizedCode, AssetKind.FUND.name)
                )
                return findAsset(normalizedCode) ?: existing
            }
            return existing
        }
        val base = AssetInfo(normalizedCode, normalizedName, AssetKind.FUND, normalizedCategory)
        val asset = base.copy(marketQuoteSecid = PortfolioQuoteRouter.defaultQuoteSecid(base))
        writableDatabase.insertWithOnConflict("assets", null, ContentValues().apply {
            put("id", asset.symbol); put("name", asset.name); put("kind", asset.kind.name); put("category", asset.category)
            put("market_quote_secid", asset.marketQuoteSecid)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return findAsset(normalizedCode) ?: asset
    }

    fun trades(): List<Trade> {
        val result = mutableListOf<Trade>()
        readableDatabase.rawQuery("""SELECT t.id,a.id,a.name,a.kind,a.category,t.side,t.day,
            t.amount,t.units,t.unit_price,t.fee,t.status,t.reversed_at,t.reversal_reason,t.memo,t.share_factor,
            ra.id,ra.name,ra.kind,ra.category,t.related_units,a.market_quote_secid,ra.market_quote_secid FROM transactions t
            JOIN assets a ON a.id=t.asset_id LEFT JOIN assets ra ON ra.id=t.related_asset_id
            ORDER BY t.day DESC,t.id DESC""", null).use { c ->
            while (c.moveToNext()) result += Trade(
                c.getLong(0), AssetInfo(c.getString(1),c.getString(2),AssetKind.valueOf(c.getString(3)),c.getString(4),c.getString(21)),
                TradeSide.valueOf(c.getString(5)),c.getString(6),BigDecimal(c.getString(7)),
                c.getString(8)?.let(::BigDecimal),c.getString(9)?.let(::BigDecimal),BigDecimal(c.getString(10)),
                c.getString(11)=="CONFIRMED", c.getString(12), c.getString(13), c.getString(14),
                c.getString(15)?.let(::BigDecimal),
                if (c.isNull(16)) null else AssetInfo(
                    c.getString(16), c.getString(17), AssetKind.valueOf(c.getString(18)), c.getString(19), c.getString(22)
                ),
                c.getString(20)?.let(::BigDecimal)
            )
        }
        return result
    }

    fun positions(): List<Position> {
        val quoteMap = mutableMapOf<String, Triple<BigDecimal,String,String>>()
        readableDatabase.rawQuery("SELECT asset_id,price,observed_at,source FROM quotes", null).use { c ->
            while (c.moveToNext()) quoteMap[c.getString(0)] = Triple(BigDecimal(c.getString(1)), c.getString(2), c.getString(3))
        }
        val manualGoldQuotes = mutableMapOf<String, ManualGoldQuote>()
        readableDatabase.rawQuery(
            "SELECT asset_id,bank_buyback_price,bank_sell_price,observed_at,valid_until FROM manual_gold_quotes", null
        ).use { c ->
            while (c.moveToNext()) manualGoldQuotes[c.getString(0)] = ManualGoldQuote(
                c.getString(0), BigDecimal(c.getString(1)), BigDecimal(c.getString(2)), c.getString(3), c.getString(4)
            )
        }
        return computePositions(trades(), quoteMap).map { position ->
            val detail = manualGoldQuotes[position.asset.symbol]?.takeIf {
                position.quoteSource == "MANUAL_BANK_BUYBACK" && position.quoteTime == it.observedAt &&
                    position.quote?.compareTo(it.bankBuybackPrice) == 0
            }
            position.copy(manualGoldQuote = detail)
        }
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
        require(side == TradeSide.BUY || side == TradeSide.SELL) { "该账本事件不属于买卖交易" }
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

    private fun invalidatePortfolioFactsFrom(db: SQLiteDatabase, day: LocalDate, changedAt: Instant? = null) {
        db.update("portfolio_snapshots", ContentValues().apply { put("valid", 0) },
            "day>=?", arrayOf(day.toString()))
        val selection: String
        val arguments: Array<String>
        if (changedAt == null) {
            selection = "day>=? AND type IN (?,?)"
            arguments = arrayOf(day.toString(), AccountCashEventType.DEPOSIT.name, AccountCashEventType.WITHDRAWAL.name)
        } else {
            selection = "(day>? OR (day=? AND (occurred_at IS NULL OR occurred_at>=?))) AND type IN (?,?)"
            arguments = arrayOf(
                day.toString(), day.toString(), changedAt.toString(),
                AccountCashEventType.DEPOSIT.name, AccountCashEventType.WITHDRAWAL.name
            )
        }
        db.update("account_cash_events", ContentValues().apply {
            putNull("portfolio_value_before")
            put("valuation_complete", 0)
        }, selection, arguments)
    }

    fun addTrade(asset: AssetInfo, side: TradeSide, amount: BigDecimal, units: BigDecimal?,
                 price: BigDecimal?, fee: BigDecimal): Long {
        require(side == TradeSide.BUY || side == TradeSide.SELL) { "该账本事件不能按买卖交易录入" }
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
        val tradeDay = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val values = ContentValues().apply {
            put("asset_id", asset.symbol); put("side", side.name); put("day", tradeDay.toString())
            put("amount", sellAmount.toPlainString()); put("units", units?.toPlainString())
            put("unit_price", price?.toPlainString()); put("fee", fee.toPlainString())
            put("status", if (confirmed) "CONFIRMED" else "PENDING")
        }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val transactionId = db.insertOrThrow("transactions", null, values)
            if (confirmed) recordTradeCashEvent(
                db, transactionId, tradeDay.toString(), side, if (side == TradeSide.SELL) sellAmount else amount, fee
            )
            invalidatePortfolioFactsFrom(db, tradeDay)
            db.setTransactionSuccessful()
            transactionId
        } finally { db.endTransaction() }
    }

    /** Records a dividend's gross amount, withholding tax and net cash receipt as one atomic event. */
    fun addCashDividend(
        asset: AssetInfo,
        grossAmount: BigDecimal,
        withholdingTax: BigDecimal = BigDecimal.ZERO,
        memo: String = ""
    ): Long {
        require(grossAmount > BigDecimal.ZERO) { "分红金额必须大于零" }
        require(withholdingTax >= BigDecimal.ZERO && withholdingTax < grossAmount) { "税费必须为正数或零，且小于分红金额" }
        val note = memo.trim()
        require(note.length <= 120) { "备注不能超过 120 个字符" }
        val day = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val occurredAt = Instant.now()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val transactionId = db.insertOrThrow("transactions", null, ContentValues().apply {
                put("asset_id", asset.symbol)
                put("side", TradeSide.CASH_DIVIDEND.name)
                put("day", day.toString())
                put("amount", grossAmount.toPlainString())
                putNull("units")
                putNull("unit_price")
                put("fee", withholdingTax.toPlainString())
                put("status", "CONFIRMED")
                put("memo", note)
            })
            recordCashDividendEvent(db, transactionId, day, grossAmount - withholdingTax, note, occurredAt)
            invalidatePortfolioFactsFrom(db, day, occurredAt)
            db.setTransactionSuccessful()
            transactionId
        } finally { db.endTransaction() }
    }

    /** Records a taxed dividend reinvested into new units without adding a cash-ledger receipt. */
    fun addDividendReinvestment(
        asset: AssetInfo,
        grossAmount: BigDecimal,
        withholdingTax: BigDecimal,
        reinvestmentPrice: BigDecimal,
        memo: String = ""
    ): Long {
        require(grossAmount > BigDecimal.ZERO) { "分红金额必须大于零" }
        require(withholdingTax >= BigDecimal.ZERO && withholdingTax < grossAmount) { "税费必须小于分红金额" }
        require(reinvestmentPrice > BigDecimal.ZERO) { "再投资净值必须大于零" }
        val note = memo.trim()
        require(note.length <= 120) { "备注不能超过 120 个字符" }
        val netAmount = grossAmount - withholdingTax
        val units = netAmount.divide(reinvestmentPrice, 8, RoundingMode.DOWN)
        require(units > BigDecimal.ZERO) { "税后分红不足以换算为最小份额" }
        val day = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val transactionId = db.insertOrThrow("transactions", null, ContentValues().apply {
                put("asset_id", asset.symbol)
                put("side", TradeSide.DIVIDEND_REINVESTMENT.name)
                put("day", day.toString())
                put("amount", grossAmount.toPlainString())
                put("units", units.toPlainString())
                put("unit_price", reinvestmentPrice.toPlainString())
                put("fee", withholdingTax.toPlainString())
                put("status", "CONFIRMED")
                put("memo", note)
            })
            positions() // Validate the combined event against the existing position history.
            invalidatePortfolioFactsFrom(db, day)
            db.setTransactionSuccessful()
            transactionId
        } finally { db.endTransaction() }
    }

    /** Applies a pure share ratio while preserving total cost and invalidating the old per-unit quote. */
    fun addShareSplit(asset: AssetInfo, shareFactor: BigDecimal, memo: String = ""): Long {
        require(shareFactor > BigDecimal.ZERO && shareFactor.compareTo(BigDecimal.ONE) != 0) {
            "份额换算比例必须大于零且不能为 1"
        }
        require(shareFactor.precision() <= 24 && shareFactor.scale() <= 12) { "份额换算比例精度过高" }
        val note = memo.trim()
        require(note.length <= 120) { "备注不能超过 120 个字符" }
        val day = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val current = positions().firstOrNull { it.asset.symbol == asset.symbol }
            require(current != null && current.units > BigDecimal.ZERO) { "该标的没有可拆分的持仓" }
            val transactionId = db.insertOrThrow("transactions", null, ContentValues().apply {
                put("asset_id", asset.symbol)
                put("side", TradeSide.SHARE_SPLIT.name)
                put("day", day.toString())
                put("amount", BigDecimal.ZERO.toPlainString())
                putNull("units")
                putNull("unit_price")
                put("fee", BigDecimal.ZERO.toPlainString())
                put("status", "CONFIRMED")
                put("memo", note)
                put("share_factor", shareFactor.toPlainString())
            })
            positions() // Recalculate later sales against the adjusted unit count.
            db.delete("quotes", "asset_id=?", arrayOf(asset.symbol))
            invalidatePortfolioFactsFrom(db, day)
            db.setTransactionSuccessful()
            transactionId
        } finally { db.endTransaction() }
    }

    /** Moves units and their weighted-average cost basis between two tracked assets atomically. */
    fun addAssetTransfer(
        sourceAsset: AssetInfo,
        destinationAsset: AssetInfo,
        sourceUnits: BigDecimal,
        destinationUnits: BigDecimal,
        declaredCost: BigDecimal,
        memo: String = ""
    ): Long {
        require(sourceAsset.symbol != destinationAsset.symbol) { "转出和转入标的不能相同" }
        require(sourceUnits > BigDecimal.ZERO && destinationUnits > BigDecimal.ZERO) { "转入和转出份额必须大于零" }
        require(declaredCost > BigDecimal.ZERO) { "请输入转移成本以供核对" }
        val note = memo.trim()
        require(note.length <= 120) { "备注不能超过 120 个字符" }
        val day = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val source = positions().firstOrNull { it.asset.symbol == sourceAsset.symbol }
                ?: error("转出标的当前没有持仓")
            require(source.units >= sourceUnits) { "转出份额超过当前持仓" }
            val expectedCost = if (source.units == sourceUnits) source.cost else
                source.cost.multiply(sourceUnits).divide(source.units, 12, RoundingMode.HALF_UP)
            require(expectedCost > BigDecimal.ZERO) { "转出份额没有可转移成本" }
            require(declaredCost.setScale(2, RoundingMode.HALF_UP) == expectedCost.setScale(2, RoundingMode.HALF_UP)) {
                "转移成本与账本加权成本不符，应为 ¥${expectedCost.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
            }
            val transactionId = db.insertOrThrow("transactions", null, ContentValues().apply {
                put("asset_id", sourceAsset.symbol)
                put("side", TradeSide.ASSET_TRANSFER.name)
                put("day", day.toString())
                put("amount", expectedCost.toPlainString())
                put("units", sourceUnits.toPlainString())
                putNull("unit_price")
                put("fee", BigDecimal.ZERO.toPlainString())
                put("status", "CONFIRMED")
                put("memo", note)
                put("related_asset_id", destinationAsset.symbol)
                put("related_units", destinationUnits.toPlainString())
            })
            positions() // Validate source units and destination cost after applying the paired transfer.
            invalidatePortfolioFactsFrom(db, day)
            db.setTransactionSuccessful()
            transactionId
        } finally { db.endTransaction() }
    }

    private fun recordCashDividendEvent(
        db: SQLiteDatabase,
        transactionId: Long,
        day: LocalDate,
        netAmount: BigDecimal,
        memo: String,
        occurredAt: Instant
    ) {
        val balance = AccountCashLedger.balance(accountCashEvents()) ?: return
        if (day.isBefore(balance.trackingStart)) return
        db.insertOrThrow("account_cash_events", null, ContentValues().apply {
            put("day", day.toString())
            put("type", AccountCashEventType.DIVIDEND.name)
            put("amount", netAmount.toPlainString())
            put("memo", memo.ifBlank { "现金分红到账" })
            put("transaction_id", transactionId)
            put("occurred_at", occurredAt.toString())
        })
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
            invalidatePortfolioFactsFrom(db, LocalDate.parse(day))
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
            val cashEventTime = if (trade.side == TradeSide.CASH_DIVIDEND) {
                accountCashEvents().singleOrNull { it.transactionId == id }?.occurredAt?.let(Instant::parse)
            } else null
            require(db.delete("transactions", "id=?", arrayOf(id.toString())) == 1) { "找不到这笔流水" }
            db.delete("account_cash_events", "transaction_id=?", arrayOf(id.toString()))
            positions() // Reject an undo that would leave a later sale above the remaining position.
            AccountCashLedger.balance(accountCashEvents()) // Reject an undo that would make cash negative.
            if (trade.side == TradeSide.CASH_DIVIDEND) {
                invalidatePortfolioFactsFrom(db, LocalDate.parse(trade.day), cashEventTime)
            } else {
                invalidatePortfolioFactsFrom(db, LocalDate.parse(trade.day))
            }
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
            invalidatePortfolioFactsFrom(db, LocalDate.parse(trade.day))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun confirmTrade(id: Long, price: BigDecimal) {
        require(price > BigDecimal.ZERO)
        val trade = trades().firstOrNull { it.id == id } ?: error("找不到这笔流水")
        require(!trade.isReversed) { "已冲正流水不能确认" }
        require(!trade.confirmed) { "这笔流水已经确认" }
        require(trade.side == TradeSide.BUY || trade.side == TradeSide.SELL) { "这类账本事件不需要补录成交价格" }
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
            invalidatePortfolioFactsFrom(db, LocalDate.parse(trade.day))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun setQuote(asset: AssetInfo, price: BigDecimal) {
        require(price > BigDecimal.ZERO)
        val db = writableDatabase
        db.beginTransaction()
        try {
            saveAssetQuote(asset, price, Instant.now(), "MANUAL", db)
            db.delete("manual_gold_quotes", "asset_id=?", arrayOf(asset.symbol))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Saves both bank sides and uses the bank's buyback price as the holding's realizable mark. */
    fun setManualBankGoldQuote(
        asset: AssetInfo,
        bankBuybackPrice: BigDecimal,
        bankSellPrice: BigDecimal,
        validityHours: Int
    ) {
        require(asset.kind == AssetKind.GOLD && asset.marketQuoteSecid == null) { "银行双向报价只能用于手动黄金资产" }
        require(bankBuybackPrice > BigDecimal.ZERO && bankSellPrice >= bankBuybackPrice) {
            "银行卖出价必须大于或等于银行买入/回购价"
        }
        require(validityHours in setOf(12, 24, 48, 72)) { "报价有效期只能选 12、24、48 或 72 小时" }
        val observedAt = Instant.now()
        val validUntil = observedAt.plusSeconds(validityHours * 60L * 60L)
        val db = writableDatabase
        db.beginTransaction()
        try {
            saveAssetQuote(asset, bankBuybackPrice, observedAt, "MANUAL_BANK_BUYBACK", db)
            val values = ContentValues().apply {
                put("asset_id", asset.symbol)
                put("bank_buyback_price", bankBuybackPrice.toPlainString())
                put("bank_sell_price", bankSellPrice.toPlainString())
                put("observed_at", observedAt.toString())
                put("valid_until", validUntil.toString())
            }
            db.insertWithOnConflict("manual_gold_quotes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("manual_gold_quote_history", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            db.delete(
                "manual_gold_quote_history",
                "asset_id=? AND id NOT IN (SELECT id FROM manual_gold_quote_history WHERE asset_id=? ORDER BY observed_at DESC,id DESC LIMIT 500)",
                arrayOf(asset.symbol, asset.symbol)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun manualGoldQuoteHistory(assetSymbol: String, limit: Int = 5): List<ManualGoldQuote> {
        val safeLimit = limit.coerceIn(1, 50)
        return buildList {
            readableDatabase.rawQuery(
                "SELECT asset_id,bank_buyback_price,bank_sell_price,observed_at,valid_until " +
                    "FROM manual_gold_quote_history WHERE asset_id=? ORDER BY observed_at DESC,id DESC LIMIT $safeLimit",
                arrayOf(assetSymbol)
            ).use { c ->
                while (c.moveToNext()) add(ManualGoldQuote(
                    c.getString(0), BigDecimal(c.getString(1)), BigDecimal(c.getString(2)), c.getString(3), c.getString(4)
                ))
            }
        }
    }

    /** Changes the asset's quote route and removes the old current mark to prevent unit/source leakage. */
    fun setAssetMarketQuoteSecid(asset: AssetInfo, secid: String?): Boolean {
        val normalized = secid?.trim()?.takeIf { it.isNotEmpty() }
        require(normalized == null || PortfolioQuoteRouter.isRouteAllowed(asset, normalized)) {
            "报价标的与资产代码或计价单位不匹配"
        }
        require(normalized == null || PortfolioQuoteRouter.quoteFor(
            asset.copy(marketQuoteSecid = normalized), marketQuotes()
        ) != null) { "当前缓存没有与资产代码和单位匹配的 ETF / 黄金行情" }
        val db = writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            val current = db.rawQuery(
                "SELECT market_quote_secid FROM assets WHERE id=? AND kind=?",
                arrayOf(asset.symbol, asset.kind.name)
            ).use { cursor ->
                if (!cursor.moveToFirst()) error("账本中找不到该资产")
                cursor.getString(0)
            }
            if (current != normalized) {
                db.update("assets", ContentValues().apply {
                    if (normalized == null) putNull("market_quote_secid") else put("market_quote_secid", normalized)
                }, "id=? AND kind=?", arrayOf(asset.symbol, asset.kind.name))
                db.delete("quotes", "asset_id=?", arrayOf(asset.symbol))
                db.delete("manual_gold_quotes", "asset_id=?", arrayOf(asset.symbol))
                changed = true
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    /** Copies fresh quotes only for portfolio assets with an explicit, unit-safe market route. */
    fun syncLinkedMarketQuotes(quotes: Map<String, MarketQuote>): Int {
        val now = Instant.now()
        val updates = positions().asSequence().filter { it.units > BigDecimal.ZERO }.mapNotNull { position ->
            val asset = position.asset
            val quote = PortfolioQuoteRouter.quoteFor(asset, quotes) ?: return@mapNotNull null
            if (!quote.price.isFinite() || quote.price <= 0.0 || quote.quoteTime <= 0 || quote.source.isBlank()) {
                return@mapNotNull null
            }
            val observedAt = Instant.ofEpochSecond(quote.quoteTime)
            if (observedAt.isAfter(now.plusSeconds(5 * 60)) ||
                java.time.Duration.between(observedAt, now) > java.time.Duration.ofHours(72)
            ) return@mapNotNull null
            asset to quote
        }.toList()
        if (updates.isEmpty()) return 0

        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            val latest = mutableMapOf<String, Pair<Instant, BigDecimal>>()
            db.rawQuery("SELECT asset_id,price,observed_at FROM quotes", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val observedAt = runCatching { Instant.parse(cursor.getString(2)) }.getOrNull() ?: continue
                    latest[cursor.getString(0)] = observedAt to BigDecimal(cursor.getString(1))
                }
            }
            updates.forEach { (asset, quote) ->
                val observedAt = Instant.ofEpochSecond(quote.quoteTime)
                val previous = latest[asset.symbol]
                if (previous != null && !observedAt.isAfter(previous.first)) return@forEach
                val price = BigDecimal.valueOf(quote.price)
                saveAssetQuote(asset, price, observedAt, quote.source, db)
                changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    private fun saveAssetQuote(
        asset: AssetInfo,
        price: BigDecimal,
        observedAt: Instant,
        source: String,
        db: SQLiteDatabase = writableDatabase
    ) {
        val timestamp = observedAt.toString()
        val values = ContentValues().apply {
            put("asset_id", asset.symbol); put("price", price.toPlainString())
            put("observed_at", timestamp); put("source", source)
        }
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            db.insertWithOnConflict("quotes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("quote_history", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    fun exportEncryptedBackup(password: CharArray): String = try {
        EncryptedBackup.encrypt(exportBackup(), password)
    } finally {
        password.fill('\u0000')
    }

    fun isEncryptedBackup(payload: String): Boolean = EncryptedBackup.isEncrypted(payload)

    fun decryptBackup(payload: String, password: CharArray): String = EncryptedBackup.decrypt(payload, password)

    private fun exportBackup(): String {
        val root = JSONObject().put("format", "market-ledger-backup").put("version", 7)
            .put("exported_at", Instant.now().toString())
        val assetArray = JSONArray()
        assets().forEach { asset ->
            assetArray.put(JSONObject().put("id", asset.symbol).put("name", asset.name)
                .put("kind", asset.kind.name).put("category", asset.category)
                .put("market_quote_secid", asset.marketQuoteSecid ?: JSONObject.NULL))
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
                .put("reversal_reason", trade.reversalReason ?: JSONObject.NULL)
                .put("memo", trade.memo)
                .put("share_factor", trade.shareFactor?.toPlainString() ?: JSONObject.NULL)
                .put("related_asset_id", trade.relatedAsset?.symbol ?: JSONObject.NULL)
                .put("related_units", trade.relatedUnits?.toPlainString() ?: JSONObject.NULL))
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
        val manualBankQuotes = JSONArray()
        readableDatabase.rawQuery(
            "SELECT asset_id,bank_buyback_price,bank_sell_price,observed_at,valid_until FROM manual_gold_quotes ORDER BY asset_id",
            null
        ).use { c ->
            while (c.moveToNext()) manualBankQuotes.put(JSONObject()
                .put("asset_id", c.getString(0))
                .put("bank_buyback_price", c.getString(1))
                .put("bank_sell_price", c.getString(2))
                .put("observed_at", c.getString(3))
                .put("valid_until", c.getString(4)))
        }
        root.put("manual_gold_quotes", manualBankQuotes)
        val manualBankQuoteHistory = JSONArray()
        readableDatabase.rawQuery(
            "SELECT asset_id,bank_buyback_price,bank_sell_price,observed_at,valid_until " +
                "FROM manual_gold_quote_history ORDER BY asset_id,observed_at,id",
            null
        ).use { c ->
            while (c.moveToNext()) manualBankQuoteHistory.put(JSONObject()
                .put("asset_id", c.getString(0))
                .put("bank_buyback_price", c.getString(1))
                .put("bank_sell_price", c.getString(2))
                .put("observed_at", c.getString(3))
                .put("valid_until", c.getString(4)))
        }
        root.put("manual_gold_quote_history", manualBankQuoteHistory)
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
        require(root.optString("format") == "market-ledger-backup" && backupVersion in 1..7) { "备份格式不受支持" }
        val restoredAssets = linkedMapOf<String, AssetInfo>()
        val assetArray = root.getJSONArray("assets")
        for (i in 0 until assetArray.length()) {
            val item = assetArray.getJSONObject(i)
            val baseAsset = AssetInfo(item.getString("id").trim().uppercase(), item.getString("name").trim(),
                AssetKind.valueOf(item.getString("kind")), item.getString("category").trim())
            val marketQuoteSecid = if (backupVersion < 6) {
                PortfolioQuoteRouter.defaultQuoteSecid(baseAsset)
            } else {
                item.optString("market_quote_secid").takeIf { it.isNotBlank() && it != "null" }
            }
            val asset = baseAsset.copy(marketQuoteSecid = marketQuoteSecid)
            require(asset.symbol.isNotBlank() && asset.name.isNotBlank() && asset.category.isNotBlank()) { "备份中存在不完整标的" }
            val boundSecid = asset.marketQuoteSecid
            require(boundSecid == null || PortfolioQuoteRouter.isRouteAllowed(asset, boundSecid)) {
                "备份中的行情绑定与标的单位不匹配"
            }
            require(restoredAssets.put(asset.symbol, asset) == null) { "备份中存在重复标的" }
        }
        val watchRows = root.optJSONArray("watchlist")
        val restoredWatchlist = if (watchRows == null) listOf("1.000001", "0.399001", "100.NDX", "100.SPX", "100.N225", "100.HSI")
            else (0 until watchRows.length()).map { watchRows.getString(it) }
        require(restoredWatchlist.distinct().size == restoredWatchlist.size &&
            restoredWatchlist.all { id ->
                MarketInstrumentCatalog.byId.containsKey(id) ||
                    restoredAssets[id]?.let { it.kind == AssetKind.FUND && id.matches(Regex("\\d{6}")) } == true
            }) { "备份中的自选列表无效" }

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
            val memo = item.optString("memo").trim()
            val shareFactor = item.optString("share_factor")
                .takeIf { it.isNotBlank() && it != "null" }?.let(::BigDecimal)
            val relatedAssetId = item.optString("related_asset_id")
                .takeIf { it.isNotBlank() && it != "null" }
            val relatedAsset = relatedAssetId?.let { restoredAssets[it] ?: error("转移流水引用了不存在的标的") }
            val relatedUnits = item.optString("related_units")
                .takeIf { it.isNotBlank() && it != "null" }?.let(::BigDecimal)
            require(status == "CONFIRMED" || status == "PENDING") { "备份中存在未知流水状态" }
            val confirmed = status == "CONFIRMED"
            require(id > 0 && transactionIds.add(id) && amount >= BigDecimal.ZERO && fee >= BigDecimal.ZERO) { "备份中存在无效流水" }
            require(memo.length <= 120) { "流水备注不能超过 120 个字符" }
            require(units == null || units > BigDecimal.ZERO)
            require(price == null || price > BigDecimal.ZERO)
            when (side) {
                TradeSide.CASH_DIVIDEND -> require(
                    confirmed && units == null && price == null && amount > fee
                ) { "现金分红状态或金额无效" }
                TradeSide.DIVIDEND_REINVESTMENT -> require(
                    confirmed && units != null && price != null && amount > fee
                ) { "红利再投状态或金额无效" }
                TradeSide.SHARE_SPLIT -> require(
                    confirmed && units == null && price == null && amount.signum() == 0 && fee.signum() == 0 &&
                        shareFactor != null && shareFactor > BigDecimal.ZERO && shareFactor.compareTo(BigDecimal.ONE) != 0 &&
                        shareFactor.precision() <= 24 && shareFactor.scale() <= 12
                ) { "份额拆分状态或比例无效" }
                TradeSide.ASSET_TRANSFER -> require(
                    confirmed && units != null && price == null && amount > BigDecimal.ZERO && fee.signum() == 0 &&
                        relatedAsset != null && relatedAsset.symbol != asset.symbol && relatedUnits != null &&
                        relatedUnits > BigDecimal.ZERO
                ) { "资产转移状态、标的或份额无效" }
                else -> require(
                    if (confirmed) units != null && price != null && amount > BigDecimal.ZERO else price == null
                ) { "流水状态与价格数据不匹配" }
            }
            require(side == TradeSide.SHARE_SPLIT || shareFactor == null) { "非份额拆分流水包含拆分比例" }
            require(side == TradeSide.ASSET_TRANSFER || (relatedAsset == null && relatedUnits == null)) {
                "非资产转移流水包含关联标的或份额"
            }
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
            restoredTrades += Trade(
                id, asset, side, day, amount, units, price, fee, confirmed, reversedAt, reversalReason, memo,
                shareFactor, relatedAsset, relatedUnits
            )
        }
        restoredTrades.sortWith(compareByDescending<Trade> { it.day }.thenByDescending { it.id })

        val restoredCashEvents = mutableListOf<AccountCashEvent>()
        val linkedTransactionIds = mutableSetOf<Long>()
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
            val linkedTransactionId = event.transactionId
            if (linkedTransactionId != null) {
                require(linkedTransactionIds.add(linkedTransactionId)) { "一笔交易不能关联多条现金流水" }
                val trade = restoredTrades.singleOrNull { it.id == linkedTransactionId && it.confirmed }
                    ?: error("现金流水引用了不存在或未确认的交易")
                require(!trade.isReversed) { "已冲正的交易不能保留关联现金流水" }
                val expectedType = when (trade.side) {
                    TradeSide.BUY -> AccountCashEventType.TRADE_BUY
                    TradeSide.SELL -> AccountCashEventType.TRADE_SELL
                    TradeSide.CASH_DIVIDEND -> AccountCashEventType.DIVIDEND
                    TradeSide.DIVIDEND_REINVESTMENT -> error("红利再投不产生账户现金流水")
                    TradeSide.SHARE_SPLIT -> error("份额拆分不产生账户现金流水")
                    TradeSide.ASSET_TRANSFER -> error("资产转移不产生账户现金流水")
                    TradeSide.OPENING_POSITION -> error("期初持仓不能关联交易现金流水")
                }
                val expectedAmount = when (trade.side) {
                    TradeSide.BUY -> trade.amount
                    TradeSide.SELL, TradeSide.CASH_DIVIDEND -> trade.amount - trade.fee
                    TradeSide.DIVIDEND_REINVESTMENT -> error("红利再投不产生账户现金流水")
                    TradeSide.SHARE_SPLIT -> error("份额拆分不产生账户现金流水")
                    TradeSide.ASSET_TRANSFER -> error("资产转移不产生账户现金流水")
                    TradeSide.OPENING_POSITION -> error("期初持仓不能关联交易现金流水")
                }
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
        val manualBankQuoteHistory = mutableListOf<ManualGoldQuote>()
        val manualBankHistoryKeys = mutableSetOf<Pair<String, String>>()
        val manualHistoryRows = if (backupVersion >= 7) {
            require(root.has("manual_gold_quote_history")) { "备份缺少银行黄金报价历史" }
            root.getJSONArray("manual_gold_quote_history")
        } else JSONArray()
        for (i in 0 until manualHistoryRows.length()) {
            val quote = manualGoldQuoteFromBackup(manualHistoryRows.getJSONObject(i))
            val asset = restoredAssets[quote.assetSymbol] ?: error("银行黄金报价历史引用了不存在的标的")
            require(asset.kind == AssetKind.GOLD) { "银行黄金报价历史标的不是黄金资产" }
            validateManualGoldQuote(quote)
            require(manualBankHistoryKeys.add(quote.assetSymbol to quote.observedAt)) { "备份中存在重复银行黄金报价历史" }
            manualBankQuoteHistory += quote
        }
        val manualBankQuotes = linkedMapOf<String, ManualGoldQuote>()
        val manualLatestRows = if (backupVersion >= 7) {
            require(root.has("manual_gold_quotes")) { "备份缺少当前银行黄金报价" }
            root.getJSONArray("manual_gold_quotes")
        } else JSONArray()
        for (i in 0 until manualLatestRows.length()) {
            val quote = manualGoldQuoteFromBackup(manualLatestRows.getJSONObject(i))
            val asset = restoredAssets[quote.assetSymbol] ?: error("银行黄金报价引用了不存在的标的")
            require(asset.kind == AssetKind.GOLD && asset.marketQuoteSecid == null) { "银行黄金报价标的不是手动黄金资产" }
            validateManualGoldQuote(quote)
            require(manualBankQuotes.put(quote.assetSymbol, quote) == null) { "备份中存在重复当前银行黄金报价" }
            val latest = restoredQuotes[quote.assetSymbol]
            require(latest?.third == "MANUAL_BANK_BUYBACK" && latest.second == quote.observedAt &&
                latest.first.compareTo(quote.bankBuybackPrice) == 0) { "银行黄金报价与当前账本报价不一致" }
            require(manualBankQuoteHistory.any { it == quote }) { "当前银行黄金报价缺少对应历史记录" }
        }
        restoredQuotes.forEach { (assetId, quote) ->
            if (quote.third == "MANUAL_BANK_BUYBACK") {
                require(manualBankQuotes.containsKey(assetId)) { "当前银行黄金报价缺少双向价格和有效期" }
            }
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
            db.delete("manual_gold_quotes", null, null)
            db.delete("manual_gold_quote_history", null, null)
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
                    put("market_quote_secid", asset.marketQuoteSecid)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            restoredTrades.forEach { trade ->
                db.insertOrThrow("transactions", null, ContentValues().apply {
                    put("id", trade.id); put("asset_id", trade.asset.symbol); put("side", trade.side.name); put("day", trade.day)
                    put("amount", trade.amount.toPlainString()); put("units", trade.units?.toPlainString())
                    put("unit_price", trade.price?.toPlainString()); put("fee", trade.fee.toPlainString())
                    put("status", if (trade.confirmed) "CONFIRMED" else "PENDING")
                    put("reversed_at", trade.reversedAt); put("reversal_reason", trade.reversalReason)
                    put("memo", trade.memo)
                    put("share_factor", trade.shareFactor?.toPlainString())
                    put("related_asset_id", trade.relatedAsset?.symbol)
                    put("related_units", trade.relatedUnits?.toPlainString())
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
            manualBankQuotes.values.forEach { quote ->
                db.insertOrThrow("manual_gold_quotes", null, manualGoldQuoteValues(quote))
            }
            manualBankQuoteHistory.forEach { quote ->
                db.insertOrThrow("manual_gold_quote_history", null, manualGoldQuoteValues(quote))
            }
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

    private fun manualGoldQuoteFromBackup(row: JSONObject) = ManualGoldQuote(
        assetSymbol = row.getString("asset_id").trim().uppercase(),
        bankBuybackPrice = BigDecimal(row.getString("bank_buyback_price")),
        bankSellPrice = BigDecimal(row.getString("bank_sell_price")),
        observedAt = row.getString("observed_at").also(Instant::parse),
        validUntil = row.getString("valid_until").also(Instant::parse)
    )

    private fun validateManualGoldQuote(quote: ManualGoldQuote) {
        require(quote.assetSymbol.isNotBlank() && quote.bankBuybackPrice > BigDecimal.ZERO &&
            quote.bankSellPrice >= quote.bankBuybackPrice) { "备份中的银行黄金双向报价无效" }
        val observedAt = Instant.parse(quote.observedAt)
        val validUntil = Instant.parse(quote.validUntil)
        val validity = java.time.Duration.between(observedAt, validUntil)
        require(validity.nano == 0 && validity.seconds in setOf(12L, 24L, 48L, 72L).map { it * 60 * 60 }) {
            "备份中的银行黄金报价有效期无效"
        }
    }

    private fun manualGoldQuoteValues(quote: ManualGoldQuote) = ContentValues().apply {
        put("asset_id", quote.assetSymbol)
        put("bank_buyback_price", quote.bankBuybackPrice.toPlainString())
        put("bank_sell_price", quote.bankSellPrice.toPlainString())
        put("observed_at", quote.observedAt)
        put("valid_until", quote.validUntil)
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
        when (trade.side) {
            TradeSide.BUY, TradeSide.OPENING_POSITION -> {
                val units = requireNotNull(trade.units)
                val price = requireNotNull(trade.price)
                p.units = p.units.add(units)
                p.cost = p.cost.add(trade.amount)
                check(price > BigDecimal.ZERO)
            }
            TradeSide.SELL -> {
                val units = requireNotNull(trade.units)
                val price = requireNotNull(trade.price)
                require(p.units >= units) { "流水记录存在超额卖出" }
                val costOut = if (p.units == units) p.cost else p.cost.multiply(units).divide(p.units, 12, RoundingMode.HALF_UP)
                p.units = p.units.subtract(units)
                p.cost = p.cost.subtract(costOut)
                p.realized = p.realized.add(trade.amount).subtract(trade.fee).subtract(costOut)
                check(price > BigDecimal.ZERO)
            }
            TradeSide.CASH_DIVIDEND -> {
                require(trade.units == null && trade.price == null) { "现金分红不能包含成交份额或净值" }
                require(trade.amount > trade.fee) { "分红税费必须小于税前分红金额" }
                p.realized = p.realized.add(trade.amount - trade.fee)
            }
            TradeSide.DIVIDEND_REINVESTMENT -> {
                val units = requireNotNull(trade.units)
                val price = requireNotNull(trade.price)
                require(units > BigDecimal.ZERO && price > BigDecimal.ZERO && trade.amount > trade.fee) {
                    "红利再投份额或金额无效"
                }
                val reinvestedAmount = units.multiply(price)
                require(reinvestedAmount <= trade.amount - trade.fee) { "红利再投份额超过税后分红金额" }
                p.units = p.units.add(units)
                p.cost = p.cost.add(reinvestedAmount)
                p.realized = p.realized.add(trade.amount - trade.fee)
            }
            TradeSide.SHARE_SPLIT -> {
                val factor = requireNotNull(trade.shareFactor)
                require(factor > BigDecimal.ZERO && factor.compareTo(BigDecimal.ONE) != 0 && p.units > BigDecimal.ZERO) {
                    "份额拆分比例或持仓无效"
                }
                p.units = p.units.multiply(factor)
            }
            TradeSide.ASSET_TRANSFER -> {
                val sourceUnits = requireNotNull(trade.units)
                val targetAsset = requireNotNull(trade.relatedAsset)
                val targetUnits = requireNotNull(trade.relatedUnits)
                require(targetAsset.symbol != trade.asset.symbol && sourceUnits > BigDecimal.ZERO &&
                    targetUnits > BigDecimal.ZERO && p.units >= sourceUnits) { "资产转移份额或标的无效" }
                val costOut = if (p.units == sourceUnits) p.cost else
                    p.cost.multiply(sourceUnits).divide(p.units, 12, RoundingMode.HALF_UP)
                require(costOut.compareTo(trade.amount) == 0) { "资产转移成本与转出份额不匹配" }
                p.units = p.units.subtract(sourceUnits)
                p.cost = p.cost.subtract(costOut)
                val target = states.getOrPut(targetAsset.symbol) { State(targetAsset) }
                target.units = target.units.add(targetUnits)
                target.cost = target.cost.add(costOut)
            }
        }
    }
    return states.values.map { p ->
        val quote = quotes[p.asset.symbol]
        Position(p.asset,p.units,p.cost,p.realized,quote?.first,quote?.second,quote?.third)
    }.filter { it.units > BigDecimal.ZERO || it.realized != BigDecimal.ZERO }
}
