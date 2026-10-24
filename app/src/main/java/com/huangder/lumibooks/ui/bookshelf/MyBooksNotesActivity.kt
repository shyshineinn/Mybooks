package com.huangder.lumibooks.ui.bookshelf

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.ui.components.ConfigurableActivityBack
import com.huangder.lumibooks.ui.components.LiquidGlassDialogHost
import com.huangder.lumibooks.ui.components.LiquidGlassIconButton
import com.huangder.lumibooks.ui.components.LiquidGlassTextButton
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.icons.directionalIcon
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.huangder.lumibooks.ui.theme.effectiveAppTheme
import com.huangder.lumibooks.ui.theme.fangSongFamily
import com.huangder.lumibooks.ui.theme.rememberLiquidGlassCapability
import com.huangder.lumibooks.ui.theme.resolveAppFontFamily
import com.huangder.lumibooks.util.LaunchThemeController
import com.huangder.lumibooks.util.markdown.MarkdownRenderer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 只读读书心得:只渲染 MyBooksNotesStore 中的本地 .md,不联网。 */
@AndroidEntryPoint
class MyBooksNotesActivity : ComponentActivity() {

    @Inject
    lateinit var dataStoreManager: DataStoreManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.huangder.lumibooks.util.LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val launchTheme = LaunchThemeController.themeSnapshot(this)
        val isSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = when (launchTheme.darkMode) {
            "dark" -> true
            "light" -> false
            else -> isSystemDark
        }

        setContent {
            val capability = rememberLiquidGlassCapability(view = LocalView.current)
            val resolvedAppTheme = effectiveAppTheme(launchTheme.appTheme, capability)
            val menuAnimationStyleValue by dataStoreManager.menuAnimationStyle.collectAsState(
                initial = launchTheme.menuAnimationStyle
            )
            EBookReaderTheme(
                menuAnimationStyle = com.huangder.lumibooks.domain.model.MenuAnimationStyle
                    .fromStoredValue(menuAnimationStyleValue),
                darkTheme = isDark,
                dynamicColor = resolvedAppTheme == "material3",
                appTheme = resolvedAppTheme,
                appAccentColor = launchTheme.appAccentColor,
                liquidGlassTransparency = launchTheme.liquidGlassTransparency,
                liquidGlassHdrHighlightEnabled = launchTheme.liquidGlassHdrHighlightEnabled,
                globalFontMode = launchTheme.globalFontMode
            ) {
                ConfigurableActivityBack(
                    predictiveBackEnabled = launchTheme.predictiveBackEnabled,
                    onBack = { finish() }
                )
                LiquidGlassDialogHost(modifier = Modifier.fillMaxSize()) {
                    MyBooksNotesScreen(
                        bookId = bookId,
                        bookTitle = title,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, bookId: String, title: String) {
            context.startActivity(
                Intent(context, MyBooksNotesActivity::class.java)
                    .putExtra(EXTRA_BOOK_ID, bookId)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }
}

@Composable
private fun MyBooksNotesScreen(
    bookId: String,
    bookTitle: String,
    onBack: () -> Unit,
    viewModel: MyBooksNotesViewModel = hiltViewModel()
) {
    val state by viewModel.readerState.collectAsState()
    LaunchedEffect(bookId) { viewModel.loadLocal(bookId) }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.PageBg)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.sm, vertical = AppSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                imageVector = directionalIcon(AppIcons.ArrowLeft, AppIcons.ArrowRight),
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
                size = 44.dp,
                iconSize = 21.dp
            )
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.mybooks_notes_title),
                    fontSize = AppType.Section,
                    fontWeight = FontWeight.Bold,
                    fontFamily = resolveAppFontFamily(fangSongFamily()),
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (bookTitle.isNotBlank()) {
                    Text(
                        "《$bookTitle》",
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.size(48.dp))
        }

        Box(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            when {
                state.loading -> LoadingNotes()
                state.error != null -> NotesError(
                    message = state.error.orEmpty(),
                    empty = !state.localHas,
                    onRetry = { viewModel.loadLocal(bookId) },
                    onBack = onBack
                )
                state.bodyHtml != null -> NotesWebView(
                    context = context,
                    bodyHtml = state.bodyHtml.orEmpty()
                )
                else -> NotesError(
                    message = stringResource(R.string.mybooks_notes_empty_title),
                    empty = true,
                    onRetry = { viewModel.loadLocal(bookId) },
                    onBack = onBack
                )
            }
        }
    }
}

@Composable
private fun LoadingNotes() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = AppColors.Accent,
            strokeWidth = 2.5.dp
        )
        Text(
            stringResource(R.string.mybooks_notes_reading_local),
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = AppSpace.sm)
        )
    }
}

@Composable
private fun NotesError(
    message: String,
    empty: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(AppSpace.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (empty) {
                stringResource(R.string.mybooks_notes_empty_title)
            } else {
                stringResource(R.string.mybooks_notes_load_failed)
            },
            fontSize = AppType.Body,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary
        )
        Text(
            if (empty) stringResource(R.string.mybooks_notes_empty_hint) else message,
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = AppSpace.sm, bottom = AppSpace.md)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
            LiquidGlassTextButton(stringResource(R.string.mybooks_notes_back_details), onBack)
            if (!empty) LiquidGlassTextButton(stringResource(R.string.retry), onRetry)
        }
    }
}

@Composable
private fun NotesWebView(context: Context, bodyHtml: String) {
    val pageBg = AppColors.PageBg
    val textPrimary = AppColors.TextPrimary
    val textSecondary = AppColors.TextSecondary
    val divider = AppColors.Divider
    val bgGray = AppColors.BgGray
    val accent = AppColors.Accent
    val html = remember(bodyHtml, pageBg, textPrimary, textSecondary, divider, bgGray, accent) {
        buildNotesHtml(
            bodyHtml = bodyHtml,
            pageBg = pageBg.toArgb(),
            textPrimary = textPrimary.toArgb(),
            textSecondary = textSecondary.toArgb(),
            divider = divider.toArgb(),
            bgGray = bgGray.toArgb(),
            accent = accent.toArgb()
        )
    }
    key(html) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(pageBg.toArgb())
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.textZoom = (context.resources.configuration.fontScale * 100).toInt()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val url = request.url.toString()
                            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                return true
                            }
                            return false
                        }
                    }
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
        )
    }
}

private fun buildNotesHtml(
    bodyHtml: String,
    pageBg: Int,
    textPrimary: Int,
    textSecondary: Int,
    divider: Int,
    bgGray: Int,
    accent: Int
): String {
    fun hex(color: Int) = String.format("#%06X", 0xFFFFFF and color)
    return """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
        ${MarkdownRenderer.STYLE}
        html, body { margin:0; padding:0; background:${hex(pageBg)}; color:${hex(textPrimary)}; }
        body { font-size:16px; line-height:1.75; text-align:justify; overflow-wrap:break-word;
               padding:8px 24px 32px; max-width:720px; margin:0 auto; }
        h1,h2,h3,h4,h5,h6 { text-indent:0; line-height:1.35; }
        h1 { font-size:24px; margin:1.2em 0 .6em; } h2 { font-size:20px; margin:1.2em 0 .6em; }
        h3 { font-size:18px; margin:1em 0 .5em; } h4 { font-size:16px; margin:1em 0 .5em; }
        body > :first-child { margin-top:0; }
        a { color:${hex(accent)}; text-decoration:none; }
        blockquote { border-inline-start:3px solid ${hex(accent)}; margin:1em 0;
                     padding-inline-start:1em; color:${hex(textSecondary)}; }
        code, pre { font-family:monospace; font-size:14px; background:${hex(bgGray)}; }
        pre { padding:12px; border-radius:14px; overflow-x:auto; white-space:pre-wrap; }
        table { width:100%; border-collapse:collapse; font-size:14px; }
        th, td { border:1px solid ${hex(divider)}; padding:8px; }
        hr { border:0; border-top:1px solid ${hex(divider)}; }
        img { max-width:100%; height:auto; }
        .md-missing-image { color:${hex(textSecondary)}; }
        </style></head><body>$bodyHtml</body></html>
    """.trimIndent()
}
