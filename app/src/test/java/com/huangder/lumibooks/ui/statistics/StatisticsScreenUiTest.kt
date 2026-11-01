package com.huangder.lumibooks.ui.statistics

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.ui.reading.PreviewSamples
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-xhdpi")
class StatisticsScreenUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun summaryAndEveryPeriodRenderWithSessionsAndEmptyState() {
        var period by mutableIntStateOf(0)
        val state = PreviewSamples.statistics(0, empty = false)
        compose.setContent {
            EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                ) {
                    Row {
                        listOf("周", "月", "年").forEachIndexed { index, label ->
                            Text(
                                text = label,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.clickable { period = index }
                            )
                            Spacer(Modifier.height(0.dp))
                        }
                    }
                    ReadingSummary(state, period)
                    when (period) {
                        0 -> WeeklyOverview(state, onPrevious = {}, onNext = {}, onOpenDay = {})
                        1 -> MonthlyHeatmap(state, onPrevious = {}, onNext = {}, onOpenDay = {})
                        else -> YearlyHeatmap(state, onPrevious = {}, onNext = {}, onOpenDay = {})
                    }
                }
            }
        }
        compose.onNodeWithText("本周阅读时长").assertIsDisplayed()
        compose.onNodeWithText("阅读次数").assertIsDisplayed()
        compose.onNodeWithText("月").performClick()
        compose.onNodeWithText("本月阅读时长").assertIsDisplayed()
        compose.onNodeWithText("年").performClick()
        compose.onNodeWithText("本年阅读时长").assertIsDisplayed()
    }

    @Test fun emptyWeekShowsCompactStateWithoutCrashing() {
        val state = PreviewSamples.statistics(0, empty = true)
        compose.setContent {
            EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ReadingSummary(state, 0)
                    WeeklyOverview(state, onPrevious = {}, onNext = {}, onOpenDay = {})
                }
            }
        }
        compose.onNodeWithText("本周还没有阅读记录").assertIsDisplayed()
        compose.onNodeWithText("开始阅读后会自动记录").assertIsDisplayed()
    }

    @Test fun darkThemeRendersTheSummary() = renderVariant("lumi", dark = true)

    @Test fun liquidGlassRendersTheSummary() = renderVariant("liquid_glass", dark = false)

    @Test fun inkModeRendersTheSummary() = renderVariant("lumi", dark = false, ink = true)

    private fun renderVariant(theme: String, dark: Boolean, ink: Boolean = false) {
        val state = PreviewSamples.statistics(0, empty = false)
        compose.setContent {
            EBookReaderTheme(
                appTheme = theme,
                darkTheme = dark,
                eInkMode = ink,
                lumiBackgroundScene = null
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ReadingSummary(state, 0)
                    WeeklyOverview(state, onPrevious = {}, onNext = {}, onOpenDay = {})
                }
            }
        }
        compose.onNodeWithText("阅读次数").assertIsDisplayed()
    }
}
