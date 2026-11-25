package org.bible.reading.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate

data class SessionScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val verticalScrollValue: Int = 0
)

data class FurtherStudyModulePosition(
    val bookOsis: String? = null,
    val chapter: Int = 1,
    val selectedVerseFilter: Int = 0,
    val selectedKeyIndex: Int = 0,
    val selectedKeyName: String? = null
)

/**
 * Tracks in-session reading progress (active module, book/chapter or section,
 * and exact scroll position per section) so switching tabs or returning to a
 * previously visited section resumes right where the user left off.
 */
class SessionReadingState {
    // DailyReadingView state
    private val dailySelectedIndexByDate = mutableMapOf<LocalDate, Int>()
    private val dailyScrollPositions = mutableMapOf<String, SessionScrollPosition>()

    // BibleReadingPlanView state
    var readingPlanConfigScrollValue: Int = 0
    var readingPlanResultsScrollPosition: SessionScrollPosition = SessionScrollPosition()

    // FurtherStudyView state
    var furtherStudyCategoryTabName: String? = null
    var furtherStudySelectedModuleInitials: String? = null
    var furtherStudyIsParallelMode: Boolean = false
    var furtherStudySecondaryModuleInitials: String? = null
    var furtherStudyIsNotesMode: Boolean = false
    var furtherStudyFontSizeSp: Int = 16
    var furtherStudyGenBookSearchQuery: String = ""

    private val furtherStudyModulePositions = mutableMapOf<String, FurtherStudyModulePosition>()
    private val furtherStudyScrollPositions = mutableMapOf<String, SessionScrollPosition>()

    fun dailySectionKey(date: LocalDate, readingIndex: Int): String =
        "daily|$date|$readingIndex"

    fun getDailySelectedIndex(date: LocalDate): Int =
        dailySelectedIndexByDate[date] ?: -1

    fun saveDailySelectedIndex(date: LocalDate, index: Int) {
        dailySelectedIndexByDate[date] = index
    }

    fun getDailyScrollPosition(date: LocalDate, readingIndex: Int): SessionScrollPosition =
        dailyScrollPositions[dailySectionKey(date, readingIndex)] ?: SessionScrollPosition()

    fun saveDailyScrollPosition(date: LocalDate, readingIndex: Int, index: Int, offset: Int) {
        dailyScrollPositions[dailySectionKey(date, readingIndex)] = SessionScrollPosition(
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = offset
        )
    }

    fun verseSectionKey(
        pane: String,
        moduleInitials: String,
        bookOsis: String,
        chapter: Int,
        verseFilter: Int
    ): String = "verse|$pane|$moduleInitials|$bookOsis|$chapter|$verseFilter"

    fun genBookSectionKey(
        moduleInitials: String,
        keyIndex: Int,
        keyName: String?
    ): String = "genbook|$moduleInitials|$keyIndex|${keyName.orEmpty()}"

    fun getModulePosition(moduleInitials: String): FurtherStudyModulePosition? =
        furtherStudyModulePositions[moduleInitials]

    fun saveVersePosition(
        moduleInitials: String,
        bookOsis: String?,
        chapter: Int,
        verseFilter: Int
    ) {
        val current = furtherStudyModulePositions[moduleInitials] ?: FurtherStudyModulePosition()
        furtherStudyModulePositions[moduleInitials] = current.copy(
            bookOsis = bookOsis,
            chapter = chapter,
            selectedVerseFilter = verseFilter
        )
    }

    fun saveGenBookPosition(
        moduleInitials: String,
        keyIndex: Int,
        keyName: String?
    ) {
        val current = furtherStudyModulePositions[moduleInitials] ?: FurtherStudyModulePosition()
        furtherStudyModulePositions[moduleInitials] = current.copy(
            selectedKeyIndex = keyIndex,
            selectedKeyName = keyName
        )
    }

    fun getSectionScrollPosition(sectionKey: String): SessionScrollPosition =
        furtherStudyScrollPositions[sectionKey] ?: SessionScrollPosition()

    fun saveSectionLazyScroll(sectionKey: String, index: Int, offset: Int) {
        val prev = furtherStudyScrollPositions[sectionKey] ?: SessionScrollPosition()
        furtherStudyScrollPositions[sectionKey] = prev.copy(
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = offset
        )
    }

    fun saveSectionVerticalScroll(sectionKey: String, value: Int) {
        val prev = furtherStudyScrollPositions[sectionKey] ?: SessionScrollPosition()
        furtherStudyScrollPositions[sectionKey] = prev.copy(
            verticalScrollValue = value
        )
    }
}

@Composable
fun rememberTrackedLazyListState(
    sectionKey: String,
    initialPosition: () -> SessionScrollPosition,
    onSavePosition: (index: Int, offset: Int) -> Unit
): LazyListState {
    val latestOnSave by rememberUpdatedState(onSavePosition)
    val saved = remember(sectionKey) { initialPosition() }
    val listState = remember(sectionKey) {
        LazyListState(
            firstVisibleItemIndex = saved.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = saved.firstVisibleItemScrollOffset
        )
    }
    LaunchedEffect(listState, sectionKey) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                latestOnSave(index, offset)
            }
    }
    return listState
}

@Composable
fun rememberTrackedScrollState(
    sectionKey: String,
    initialValue: () -> Int,
    onSaveValue: (Int) -> Unit
): ScrollState {
    val latestOnSave by rememberUpdatedState(onSaveValue)
    val saved = remember(sectionKey) { initialValue() }
    val scrollState = remember(sectionKey) { ScrollState(initial = saved) }
    LaunchedEffect(scrollState, sectionKey) {
        snapshotFlow { scrollState.value }
            .distinctUntilChanged()
            .collect { value ->
                latestOnSave(value)
            }
    }
    return scrollState
}
