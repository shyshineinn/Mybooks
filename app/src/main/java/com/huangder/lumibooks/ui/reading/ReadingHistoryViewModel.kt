package com.huangder.lumibooks.ui.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huangder.lumibooks.domain.repository.BookRepository
import com.huangder.lumibooks.domain.repository.ReadingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class ReadingHistoryPeriod { ALL, WEEK, MONTH, YEAR, DAY }

data class ReadingHistoryFilter(
    val bookId: String? = null,
    val period: ReadingHistoryPeriod = ReadingHistoryPeriod.ALL,
    val date: String? = null,
    val mode: ReadingHistoryModeFilter = ReadingHistoryModeFilter.ALL
)

enum class ReadingHistoryModeFilter { ALL, PAGED, SCROLL }

data class ReadingHistoryBookOption(val id: String, val title: String)

data class ReadingHistoryUiState(
    val sessions: List<ReadingSessionItem> = emptyList(),
    val books: List<ReadingHistoryBookOption> = emptyList(),
    val filter: ReadingHistoryFilter = ReadingHistoryFilter(),
    val loading: Boolean = true
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

@HiltViewModel
class ReadingHistoryViewModel @Inject constructor(
    private val readingRepository: ReadingRepository,
    private val bookRepository: BookRepository
) : ViewModel() {

    private val filter = MutableStateFlow(ReadingHistoryFilter())

    val uiState = combine(
        readingRepository.observeAllReadingSessions(),
        bookRepository.getAllBooks(),
        filter
    ) { sessions, books, currentFilter ->
        val joined = sessions.joinBooks(books)
        ReadingHistoryUiState(
            sessions = joined.filter { item -> item.matches(currentFilter) },
            books = joined.map { ReadingHistoryBookOption(it.bookId, it.bookTitle) }
                .distinctBy { it.id }
                .sortedBy { it.title },
            filter = currentFilter,
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingHistoryUiState())

    fun initialize(bookId: String?, date: String?) {
        if (bookId.isNullOrBlank() && date.isNullOrBlank()) return
        filter.update {
            it.copy(
                bookId = bookId?.takeIf { id -> id.isNotBlank() },
                date = date?.takeIf { value -> value.isNotBlank() },
                period = if (date.isNullOrBlank()) it.period else ReadingHistoryPeriod.DAY
            )
        }
    }

    fun setBook(bookId: String?) {
        filter.update { it.copy(bookId = bookId) }
    }

    fun setPeriod(period: ReadingHistoryPeriod) {
        filter.update { it.copy(period = period, date = null) }
    }

    fun setDate(date: String?) {
        filter.update {
            it.copy(
                date = date,
                period = if (date == null) ReadingHistoryPeriod.ALL else ReadingHistoryPeriod.DAY
            )
        }
    }

    fun setMode(mode: ReadingHistoryModeFilter) {
        filter.update { it.copy(mode = mode) }
    }

    fun reset() {
        filter.value = ReadingHistoryFilter()
    }

    fun updateNote(sessionId: Long, note: String?, mood: String?) {
        viewModelScope.launch {
            runCatching { readingRepository.updateReadingSessionNote(sessionId, note, mood) }
        }
    }

    fun delete(sessionId: Long) {
        viewModelScope.launch {
            runCatching { readingRepository.deleteReadingSession(sessionId) }
        }
    }

    private fun ReadingSessionItem.matches(filter: ReadingHistoryFilter): Boolean {
        if (filter.bookId != null && bookId != filter.bookId) return false
        if (filter.mode == ReadingHistoryModeFilter.PAGED && mode.isScrollMode()) return false
        if (filter.mode == ReadingHistoryModeFilter.SCROLL && !mode.isScrollMode()) return false
        val localDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return false
        val today = LocalDate.now()
        val exactDate = filter.date
        if (exactDate != null) return date == exactDate
        return when (filter.period) {
            ReadingHistoryPeriod.ALL -> true
            ReadingHistoryPeriod.WEEK -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                !localDate.isBefore(start) && !localDate.isAfter(start.plusDays(6))
            }
            ReadingHistoryPeriod.MONTH ->
                localDate.year == today.year && localDate.monthValue == today.monthValue
            ReadingHistoryPeriod.YEAR -> localDate.year == today.year
            ReadingHistoryPeriod.DAY -> true
        }
    }

    private fun String.isScrollMode(): Boolean =
        this == "continuous" || this == "scroll" || this == "vertical"
}
