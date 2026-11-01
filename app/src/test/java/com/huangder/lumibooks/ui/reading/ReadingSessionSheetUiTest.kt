package com.huangder.lumibooks.ui.reading

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.huangder.lumibooks.domain.model.ReadingSession
import com.huangder.lumibooks.ui.components.LiquidGlassDialogHost
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-xhdpi")
class ReadingSessionSheetUiTest {
    @get:Rule val compose = createComposeRule()

    private val item = ReadingSessionItem(
        session = ReadingSession(
            id = 1,
            sessionKey = "sheet-session",
            bookId = "book",
            date = "2026-11-01",
            startTime = 1_000L,
            endTime = 1_921_000L,
            duration = 32 * 60_000L,
            startChapterIndex = 2,
            endChapterIndex = 3,
            startProgress = 0.30f,
            endProgress = 0.36f,
            mode = "slide"
        ),
        bookTitle = "愤怒的葡萄",
        bookAuthor = "作者",
        coverPath = null
    )

    @Test fun opensInReadModeAndKeepsFailedDraftVisible() {
        var savedMood: String? = null
        compose.setContent {
            EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
                LiquidGlassDialogHost(Modifier.fillMaxSize()) {
                    ReadingSessionDetailSheet(
                        item = item,
                        onDismiss = {},
                        onSaveNote = { _, mood ->
                            savedMood = mood
                            false
                        }
                    )
                }
            }
        }
        compose.onNodeWithText("本次阅读").assertIsDisplayed()
        compose.onNodeWithText("暂无心得").assertIsDisplayed()
        compose.onNodeWithText("编辑心得").assertIsDisplayed()
        compose.onNodeWithText("编辑心得").performClick()
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("平静").performClick()
        compose.onNodeWithTag("reading_session_note_field").performTextInput("今天读得很慢")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { savedMood == "calm" }
        compose.onNodeWithText("保存失败，请重试").assertIsDisplayed()
        compose.onNodeWithTag("reading_session_note_field").assertIsDisplayed()
        assertTrue(savedMood == "calm")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("编辑心得").assertIsDisplayed()
    }

    @Test fun successfulSaveClosesEditingAndKeepsBothFields() {
        var savedNote: String? = null
        var savedMood: String? = null
        var allowSave by mutableStateOf(false)
        compose.setContent {
            EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
                LiquidGlassDialogHost(Modifier.fillMaxSize()) {
                    ReadingSessionDetailSheet(
                        item = item,
                        onDismiss = {},
                        onSaveNote = { note, mood ->
                            savedNote = note
                            savedMood = mood
                            allowSave = true
                            true
                        }
                    )
                }
            }
        }
        compose.onNodeWithText("编辑心得").performClick()
        compose.onNodeWithTag("reading_session_note_field").performTextClearance()
        compose.onNodeWithTag("reading_session_note_field").performTextInput("新的心得")
        compose.onNodeWithText("困惑").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { allowSave && savedNote == "新的心得" }
        assertEquals("confused", savedMood)
        compose.onNodeWithText("编辑心得").assertIsDisplayed()
        compose.onNodeWithText("新的心得").assertIsDisplayed()
    }
}
