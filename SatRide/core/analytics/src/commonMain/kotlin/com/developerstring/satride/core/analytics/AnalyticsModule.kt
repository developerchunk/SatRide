package com.developerstring.satride.core.analytics

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The reporting backends, resolved per platform.
 *
 * Both are singletons: Firebase's own handles are process-wide, and a second wrapper around them
 * would only duplicate the breadcrumb trail.
 */
fun analyticsModule(): Module = module {
    single<Analytics> { createAnalytics() }
    single<CrashReporter> { createCrashReporter() }
}

internal expect fun createAnalytics(): Analytics

internal expect fun createCrashReporter(): CrashReporter
