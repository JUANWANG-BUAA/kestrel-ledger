package cn.market.assistant.domain.market

enum class DataFetchStatus { SUCCESS, PARTIAL, FAILURE }

/** A redacted record of one provider request; it never contains account or portfolio data. */
data class DataSourceRun(
    val id: Long,
    val source: String,
    val dataset: String,
    val status: DataFetchStatus,
    val requestedAt: Long,
    val itemCount: Int,
    val expectedCount: Int?,
    val errorCode: String?
)

data class DailyHistoryCoverage(
    val instrumentId: String,
    val barCount: Int,
    val firstDay: String,
    val lastDay: String,
    val lastFetchedAt: Long,
    /** Number of sessions in the latest reference-calendar window (0 when unavailable). */
    val expectedSessions: Int = 0,
    /** Expected sessions that are absent from this instrument's cached daily bars. */
    val missingSessions: List<String> = emptyList(),
    /** Same-market instrument whose observed bars define the reference session dates. */
    val calendarReferenceId: String? = null,
    val calendarAvailable: Boolean = false
)

data class DailyHistoryGapBackfillResult(
    val calendarAvailable: Boolean,
    val calendarReferenceId: String?,
    val calendarRefreshed: Boolean,
    val attemptedSessions: Int,
    val fetchedBars: Int,
    val remainingMissingSessions: List<String>,
    val source: String?
)
