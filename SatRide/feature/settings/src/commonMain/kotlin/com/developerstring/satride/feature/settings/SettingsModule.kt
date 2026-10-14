package com.developerstring.satride.feature.settings

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin definitions owned by this feature. Aggregated by composeApp's app module.
 *
 * The app name and version are passed in as plain strings rather than the shell's `AppInfo`: that
 * type lives in `composeApp`, which every feature is below, so depending on it would invert the
 * module graph for the sake of two strings.
 */
fun settingsModule(appName: String, appVersion: String): Module = module {
    viewModel { SettingsViewModel(settings = get(), appName = appName, appVersion = appVersion) }
}
