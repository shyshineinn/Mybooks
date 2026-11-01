package com.huangder.lumibooks.ui.bookshelf

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.ui.reading.PreviewSamples
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.huangder.lumibooks.ui.theme.LocalCardOutlinesEnabled
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-xhdpi")
class BookReadingTabUiTest {
    @get:Rule val compose = createComposeRule()

    private fun render(
        serverMeta: ServerBookMeta? = null,
        dark: Boolean = false,
        eInk: Boolean = false,
        fontScale: Float = 1f,
        outlines: Boolean = false
    ) {
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale),
                LocalCardOutlinesEnabled provides outlines
            ) {
                EBookReaderTheme(
                    appTheme = if (dark) "lumi" else "lumi",
                    darkTheme = dark,
                    eInkMode = eInk,
                    lumiBackgroundScene = null
                ) {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 96.dp)
                        ) {
                            item {
                                BookReadingTab(
                                    book = PreviewSamples.bookState(true).book!!,
                                    state = PreviewSamples.bookState(true),
                                    serverMeta = serverMeta,
                                    onOpenHistory = {},
                                    onOpenAnnotations = {},
                                    onOpenSession = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun overviewJourneyAndAnnotationsStayAboveTheBottomBar() {
        render()
        compose.onNodeWithText("阅读概况").assertIsDisplayed()
        compose.onNodeWithText("阅读次数").assertIsDisplayed()
        compose.onNodeWithText("阅读历程").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("划线与笔记").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("还没有添加划线或笔记").assertDoesNotExist()
        compose.onNodeWithText("服务器阅读状态").assertDoesNotExist()
    }

    @Test fun serverReadingStateIsACardSeparateFromSessionJourney() {
        val meta = ServerBookMeta(
            remoteId = 1,
            title = "愤怒的葡萄",
            author = "作者",
            status = "reading",
            summary = "",
            tags = emptyList(),
            rating = null,
            seriesLabel = null,
            series = null,
            seriesIndex = 0,
            publisher = null,
            pubYear = null,
            isbn = null,
            doubanId = null,
            startDate = "2026-10-01",
            finishDate = null,
            notesUrl = null,
            readCount = 1,
            readLogs = emptyList(),
            identifiers = emptyList(),
            customDisplay = emptyMap(),
            files = emptyList()
        )
        render(serverMeta = meta)
        compose.onNodeWithText("服务器阅读状态").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("阅读历程").performScrollTo().assertIsDisplayed()
    }

    @Test fun largeTextAndDarkThemeKeepSectionsReachable() {
        render(dark = true, fontScale = 1.6f)
        compose.onNodeWithText("划线与笔记").performScrollTo().assertIsDisplayed()
    }

    @Test fun inkModeAndOutlinesStillRender() {
        render(eInk = true, outlines = true)
        compose.onNodeWithText("阅读概况").assertIsDisplayed()
        compose.onNodeWithText("划线与笔记").performScrollTo().assertIsDisplayed()
    }

    @Test @Config(qualifiers = "zh-rCN-w852dp-h393dp-xhdpi")
    fun landscapeKeepsTheReadingProfileScrollable() {
        render()
        compose.onNodeWithText("阅读概况").assertIsDisplayed()
        compose.onNodeWithText("划线与笔记").performScrollTo().assertIsDisplayed()
    }
}
