package com.huangder.lumibooks.ui.reading

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.huangder.lumibooks.ui.theme.effectiveAppTheme
import com.huangder.lumibooks.ui.theme.rememberLiquidGlassCapability
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Chronological reading log. Entries are real sessions, never inferred daily totals. */
@AndroidEntryPoint
class ReadingHistoryActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_DATE = "date"

        fun start(context: Context, bookId: String? = null, date: String? = null) {
            context.startActivity(
                Intent(context, ReadingHistoryActivity::class.java)
                    .putExtra(EXTRA_BOOK_ID, bookId)
                    .putExtra(EXTRA_DATE, date)
            )
        }
    }

    @Inject
    lateinit var dataStoreManager: DataStoreManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.huangder.lumibooks.util.LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initialBookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val initialDate = intent.getStringExtra(EXTRA_DATE)

        val launchTheme = com.huangder.lumibooks.util.LaunchThemeController.themeSnapshot(this)
        setContent {
            val appTheme by dataStoreManager.appTheme.collectAsState(initial = launchTheme.appTheme)
            val appAccentColor by dataStoreManager.appAccentColor.collectAsState(initial = launchTheme.appAccentColor)
            val appFontSize by dataStoreManager.appFontSize.collectAsState(initial = launchTheme.appFontSize)
            val globalFontMode by dataStoreManager.globalFontMode.collectAsState(initial = launchTheme.globalFontMode)
            val liquidGlassTransparency by dataStoreManager.liquidGlassTransparency.collectAsState(initial = launchTheme.liquidGlassTransparency)
            val liquidGlassHdrHighlightEnabled by dataStoreManager.liquidGlassHdrHighlightEnabled.collectAsState(initial = launchTheme.liquidGlassHdrHighlightEnabled)
            val darkMode by dataStoreManager.darkMode.collectAsState(initial = launchTheme.darkMode)
            val predictiveBackEnabled by dataStoreManager.predictiveBackEnabled.collectAsState(initial = launchTheme.predictiveBackEnabled)
            val isDark = when (darkMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val capability = rememberLiquidGlassCapability(view = LocalView.current)
            val resolvedAppTheme = effectiveAppTheme(appTheme, capability)

            val eInkMode by dataStoreManager.eInkModeEnabled.collectAsState(initial = launchTheme.eInkModeEnabled)
            val motion by dataStoreManager.motionPreference.collectAsState(initial = launchTheme.motionPreference)
            val menuAnimationStyleValue by dataStoreManager.menuAnimationStyle.collectAsState(initial = launchTheme.menuAnimationStyle)
            val liquidGlassStyle by dataStoreManager.liquidGlassStyle
                .collectAsState(initial = launchTheme.liquidGlassStyle)
            val reduceTransparencyEnabled by dataStoreManager.reduceTransparencyEnabled
                .collectAsState(initial = launchTheme.reduceTransparencyEnabled)
            val cardOutlinesEnabled by dataStoreManager.cardOutlinesEnabled
                .collectAsState(initial = launchTheme.cardOutlinesEnabled)

            EBookReaderTheme(
                liquidGlassStyle = liquidGlassStyle,
                reduceTransparencyEnabled = reduceTransparencyEnabled,
                menuAnimationStyle = com.huangder.lumibooks.domain.model.MenuAnimationStyle.fromStoredValue(menuAnimationStyleValue),
                motionPreference = com.huangder.lumibooks.ui.theme.MotionPreference.fromStoredValue(motion),
                eInkMode = eInkMode,
                darkTheme = isDark,
                dynamicColor = resolvedAppTheme == "material3",
                appTheme = appTheme,
                lumiBackgroundScene = com.huangder.lumibooks.ui.theme.LumiBackgroundScene.SECONDARY,
                appAccentColor = appAccentColor,
                liquidGlassTransparency = liquidGlassTransparency,
                liquidGlassHdrHighlightEnabled = liquidGlassHdrHighlightEnabled,
                cardOutlinesEnabled = cardOutlinesEnabled,
                appFontSize = appFontSize,
                globalFontMode = globalFontMode
            ) {
                com.huangder.lumibooks.ui.components.ConfigurableActivityBack(
                    predictiveBackEnabled = predictiveBackEnabled,
                    onBack = { finish() }
                )
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = com.huangder.lumibooks.ui.theme.AppColors.PageBg
                ) {
                    com.huangder.lumibooks.ui.components.LiquidGlassDialogHost(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        ReadingHistoryScreen(
                            onBack = { finish() },
                            initialBookId = initialBookId,
                            initialDate = initialDate
                        )
                    }
                }
            }
        }
    }
}
