package com.huangder.lumibooks.util

import com.huangder.lumibooks.domain.model.AppIconStyle

internal object LauncherComponentNames {
    const val LUMI_CHAN_SPLASH = "com.huangder.lumibooks.ui.splash.LumiChanSplashLauncherActivity"
    const val LUMI_CHAN_DIRECT = "com.huangder.lumibooks.ui.splash.LumiChanDirectLauncherActivity"
    const val LUMI_2_SPLASH =
        "com.huangder.lumibooks.ui.splash.SplashLauncherActivity"
    const val LUMI_2_DIRECT =
        "com.huangder.lumibooks.ui.splash.DirectLauncherActivity"
    const val CLASSIC_SPLASH =
        "com.huangder.lumibooks.ui.splash.ClassicSplashLauncherActivity"
    const val CLASSIC_DIRECT =
        "com.huangder.lumibooks.ui.splash.ClassicDirectLauncherActivity"
    const val BOOK_LINE_SPLASH =
        "com.huangder.lumibooks.ui.splash.BookLineSplashLauncherActivity"
    const val BOOK_LINE_DIRECT =
        "com.huangder.lumibooks.ui.splash.BookLineDirectLauncherActivity"

    /**
     * 组件名 → manifest 里 `android:enabled` 的默认值。
     * 和上面的组件名放在同一处：新增别名时漏改这张表会让启动时
     * `manifestDefaults.getValue(name)` 抛 NoSuchElementException（App 秒退）。
     */
    val manifestDefaults: Map<String, Boolean> = linkedMapOf(
        LUMI_CHAN_SPLASH to false,
        LUMI_CHAN_DIRECT to false,
        LUMI_2_SPLASH to true,
        LUMI_2_DIRECT to false,
        CLASSIC_SPLASH to false,
        CLASSIC_DIRECT to false,
        BOOK_LINE_SPLASH to false,
        BOOK_LINE_DIRECT to false
    )
}

internal fun launcherComponentStates(
    style: String?,
    splashEnabled: Boolean,
    easterEggUnlocked: Boolean = false
): Map<String, Boolean> {
    val normalizedStyle = AppIconStyle.normalize(style, easterEggUnlocked)
    return linkedMapOf(
        LauncherComponentNames.LUMI_CHAN_SPLASH to
            (normalizedStyle == AppIconStyle.LUMI_CHAN.storedValue && splashEnabled),
        LauncherComponentNames.LUMI_CHAN_DIRECT to
            (normalizedStyle == AppIconStyle.LUMI_CHAN.storedValue && !splashEnabled),
        LauncherComponentNames.LUMI_2_SPLASH to
            (normalizedStyle == AppIconStyle.LUMI_2.storedValue && splashEnabled),
        LauncherComponentNames.LUMI_2_DIRECT to
            (normalizedStyle == AppIconStyle.LUMI_2.storedValue && !splashEnabled),
        LauncherComponentNames.CLASSIC_SPLASH to
            (normalizedStyle == AppIconStyle.CLASSIC.storedValue && splashEnabled),
        LauncherComponentNames.CLASSIC_DIRECT to
            (normalizedStyle == AppIconStyle.CLASSIC.storedValue && !splashEnabled),
        LauncherComponentNames.BOOK_LINE_SPLASH to
            (normalizedStyle == AppIconStyle.BOOK_LINE.storedValue && splashEnabled),
        LauncherComponentNames.BOOK_LINE_DIRECT to
            (normalizedStyle == AppIconStyle.BOOK_LINE.storedValue && !splashEnabled)
    )
}
