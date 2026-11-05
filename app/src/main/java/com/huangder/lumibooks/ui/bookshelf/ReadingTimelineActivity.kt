package com.huangder.lumibooks.ui.bookshelf

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.ui.components.ConfigurableActivityBack
import com.huangder.lumibooks.ui.components.LiquidGlassDialogHost
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.huangder.lumibooks.ui.theme.LumiBackgroundHost
import com.huangder.lumibooks.ui.theme.LumiBackgroundScene
import com.huangder.lumibooks.ui.theme.effectiveAppTheme
import com.huangder.lumibooks.ui.theme.rememberLiquidGlassCapability
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Uses the same system Activity transition as BookReviewActivity; no animation override. */
@AndroidEntryPoint
class ReadingTimelineActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_BOOK_TITLE = "bookTitle"

        fun start(context: Context, bookId: String, title: String) {
            context.startActivity(Intent(context, ReadingTimelineActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId).putExtra(EXTRA_BOOK_TITLE, title))
        }
    }

    @Inject lateinit var dataStoreManager: DataStoreManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.huangder.lumibooks.util.LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
        val launchTheme = com.huangder.lumibooks.util.LaunchThemeController.themeSnapshot(this)
        setContent {
            val appTheme by dataStoreManager.appTheme.collectAsState(initial = launchTheme.appTheme)
            val accent by dataStoreManager.appAccentColor.collectAsState(initial = launchTheme.appAccentColor)
            val appFontSize by dataStoreManager.appFontSize.collectAsState(initial = launchTheme.appFontSize)
            val font by dataStoreManager.globalFontMode.collectAsState(initial = launchTheme.globalFontMode)
            val transparency by dataStoreManager.liquidGlassTransparency.collectAsState(initial = launchTheme.liquidGlassTransparency)
            val hdr by dataStoreManager.liquidGlassHdrHighlightEnabled.collectAsState(initial = launchTheme.liquidGlassHdrHighlightEnabled)
            val darkMode by dataStoreManager.darkMode.collectAsState(initial = launchTheme.darkMode)
            val eInk by dataStoreManager.eInkModeEnabled.collectAsState(initial = launchTheme.eInkModeEnabled)
            val cardOutlines by dataStoreManager.cardOutlinesEnabled.collectAsState(initial = launchTheme.cardOutlinesEnabled)
            val predictiveBack by dataStoreManager.predictiveBackEnabled.collectAsState(initial = launchTheme.predictiveBackEnabled)
            val motion by dataStoreManager.motionPreference.collectAsState(initial = launchTheme.motionPreference)
            val menuStyle by dataStoreManager.menuAnimationStyle.collectAsState(initial = launchTheme.menuAnimationStyle)
            val glassStyle by dataStoreManager.liquidGlassStyle.collectAsState(initial = launchTheme.liquidGlassStyle)
            val reduceTransparency by dataStoreManager.reduceTransparencyEnabled.collectAsState(initial = launchTheme.reduceTransparencyEnabled)
            val isDark = if (eInk) false else when (darkMode) {
                "dark" -> true
                "light" -> false
                else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            val capability = rememberLiquidGlassCapability(eInk, LocalView.current)
            val resolvedTheme = effectiveAppTheme(appTheme, capability)
            EBookReaderTheme(
                liquidGlassStyle = glassStyle,
                reduceTransparencyEnabled = reduceTransparency,
                menuAnimationStyle = com.huangder.lumibooks.domain.model.MenuAnimationStyle.fromStoredValue(menuStyle),
                motionPreference = com.huangder.lumibooks.ui.theme.MotionPreference.fromStoredValue(motion),
                darkTheme = isDark, dynamicColor = resolvedTheme == "material3", appTheme = appTheme,
                lumiBackgroundScene = LumiBackgroundScene.SECONDARY,
                appAccentColor = accent, liquidGlassTransparency = transparency,
                liquidGlassHdrHighlightEnabled = hdr && !eInk, eInkMode = eInk,
                cardOutlinesEnabled = cardOutlines, appFontSize = appFontSize, globalFontMode = font
            ) {
                ConfigurableActivityBack(predictiveBackEnabled = predictiveBack, onBack = { finish() })
                LumiBackgroundHost(scene = LumiBackgroundScene.SECONDARY, modifier = Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxSize(), color = AppColors.PageBg) {
                        LiquidGlassDialogHost(Modifier.fillMaxSize()) {
                            ReadingTimelineRoute(bookId, title, onBack = { finish() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingTimelineRoute(
    bookId: String,
    bookTitle: String,
    onBack: () -> Unit,
    detailsViewModel: BookDetailsViewModel = hiltViewModel(),
    editViewModel: MyBooksEditViewModel = hiltViewModel()
) {
    val details by detailsViewModel.uiState.collectAsState()
    val metaState by editViewModel.metaState.collectAsState()
    LaunchedEffect(bookId) { detailsViewModel.load(bookId) }
    LaunchedEffect(details.book?.id) { details.book?.let { editViewModel.loadMeta(it) } }
    val events = remember(metaState.meta) { metaState.meta?.let(::readingTimelineEvents).orEmpty() }
    ReadingTimelineDetailsScreen(
        bookTitle = details.book?.title ?: bookTitle,
        meta = metaState.meta,
        events = events,
        onBack = onBack,
        loading = details.isLoading || (metaState.loading && metaState.meta == null)
    )
}
