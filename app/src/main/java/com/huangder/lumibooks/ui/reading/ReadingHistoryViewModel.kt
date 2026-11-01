package com.huangder.lumibooks.ui.reading

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huangder.lumibooks.R
import com.huangder.lumibooks.domain.repository.BookRepository
import com.huangder.lumibooks.domain.repository.ReadingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class ReadingHistoryPeriod(@StringRes val labelRes: Int) {
    ALL(R.string.reading_history_period_all),
    WEEK(R.string.reading_history_period_week),
    MONTH(R.string.reading_history_period_month),
    YEAR(R.string.reading_history_period_year),
    DAY(R.string.reading_history_period_all)
}

data class ReadingHistoryFilter(
    val bookId: String? = null,
    val period: ReadingHistoryPeriod = ReadingHistoryPeriod.ALL,
    val date: String? = null,
    val mode: ReadingHistoryModeFilter = ReadingHistoryModeFilter.ALL
)

enum class ReadingHistoryModeFilter { ALL, PAGED, SCROLL }

data class ReadingHistoryQuery(
    val bookId: String?,
    val startDate: String?,
    val endDate: String?,
    /** SQL mode key: scroll, paged, or null for all. */
    val mode: String?
)

fun ReadingHistoryFilter.toQuery(today: LocalDate = LocalDate.now()): ReadingHistoryQuery {
    val exactDate = date?.takeIf { it.isNotBlank() }
    val range = when {
        exactDate != null -> exactDate to exactDate
        period == ReadingHistoryPeriod.WEEK -> {
            val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            start.toString() to start.plusDays(6).toString()
        }
        period == ReadingHistoryPeriod.MONTH -> {
            val start = today.withDayOfMonth(1)
            start.toString() to start.withDayOfMonth(today.lengthOfMonth()).toString()
        }
        period == ReadingHistoryPeriod.YEAR -> {
            LocalDate.of(today.year, 1, 1).toString() to LocalDate.of(today.year, 12, 31).toString()
        }
        else -> null
    }
    return ReadingHistoryQuery(
        bookId = bookId?.takeIf { it.isNotBlank() },
        startDate = range?.first,
        endDate = range?.second,
        mode = when (mode) {
            ReadingHistoryModeFilter.PAGED -> "paged"
            ReadingHistoryModeFilter.SCROLL -> "scroll"
            ReadingHistoryModeFilter.ALL -> null
        }
    )
}

data class ReadingHistoryBookOption(val id: String, val title: String)

data class ReadingHistoryUiState(
    val sessions: List<ReadingSessionItem> = emptyList(),
    val books: List<ReadingHistoryBookOption> = emptyList(),
    val filter: ReadingHistoryFilter = ReadingHistoryFilter(),
    val loading: Boolean = true,
    val hasMore: Boolean = false
)

data class ReadingDayGroup(
    val date: String,
    val sessions: List<ReadingSessionItem>
) {
    val totalDuration: Long get() = sessions.sumOf { it.duration }
}

fun List<ReadingSessionItem>.groupByDay(): List<ReadingDayGroup> =
    groupBy { it.date }
        .toSortedMap(compareByDescending { it })
        .map { (date, items) -> ReadingDayGroup(date, items.sortedByDescending { it.startTime }) }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReadingHistoryViewModel @Inject constructor(
    private val readingRepository: ReadingRepository,
    private val bookRepository: BookRepository
) : ViewModel() {

    private data class HistoryRequest(
        val filter: ReadingHistoryFilter,
        val limit: Int
    )

    private val request = MutableStateFlow(HistoryRequest(ReadingHistoryFilter(), PAGE_SIZE))

    private val books = combine(
        readingRepository.observeSessionBookIds(),
        bookRepository.getAllBooks()
    ) { ids, allBooks ->
        val byId = allBooks.associateBy { it.id }
        ids.mapNotNull { id -> byId[id]?.let { ReadingHistoryBookOption(it.id, it.title) } }
            .distinctBy { it.id }
            .sortedBy { it.title }
    }

    private val results = request.flatMapLatest { current ->
        val query = current.filter.toQuery()
        readingRepository.observeSessionsFiltered(
            bookId = query.bookId,
            startDate = query.startDate,
            endDate = query.endDate,
            mode = query.mode,
            limit = current.limit
        ).combine(bookRepository.getAllBooks()) { rows, allBooks ->
            current to rows.joinBooks(allBooks)
        }
    }

    val uiState = combine(request, results, books) { current, (resultRequest, rows), options ->
        val resultReady = resultRequest == current
        ReadingHistoryUiState(
            sessions = if (resultReady) rows else emptyList(),
            books = options,
            filter = current.filter,
            loading = !resultReady,
            hasMore = resultReady && rows.size >= current.limit
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingHistoryUiState())

    fun initialize(bookId: String?, date: String?) {
        if (bookId.isNullOrBlank() && date.isNullOrBlank()) return
        val current = request.value.filter
        applyFilter(
            current.copy(
                bookId = bookId?.takeIf { it.isNotBlank() } ?: current.bookId,
                date = date?.takeIf { it.isNotBlank() },
                period = if (date.isNullOrBlank()) current.period else ReadingHistoryPeriod.DAY
            )
        )
    }

    fun applyFilter(newFilter: ReadingHistoryFilter) {
        request.value = HistoryRequest(newFilter, PAGE_SIZE)
    }

    fun reset() = applyFilter(ReadingHistoryFilter())

    fun setDate(date: String?) {
        val current = request.value.filter
        applyFilter(
            current.copy(
                date = date,
                period = if (date == null) ReadingHistoryPeriod.ALL else ReadingHistoryPeriod.DAY
            )
        )
    }

    fun loadMore() {
        request.update { it.copy(limit = it.limit + PAGE_SIZE) }
    }

    suspend fun saveNote(sessionId: Long, note: String?, mood: String?): Boolean =
        readingRepository.updateReadingSessionNote(sessionId, note, mood)

    fun delete(sessionId: Long) {
        viewModelScope.launch {
            runCatching { readingRepository.deleteReadingSession(sessionId) }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
