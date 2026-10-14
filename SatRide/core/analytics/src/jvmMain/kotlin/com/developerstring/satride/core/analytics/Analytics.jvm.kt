package com.developerstring.satride.core.analytics

/** The desktop build is a development target and is not measured. */
internal actual fun createAnalytics(): Analytics = NoOpAnalytics

internal actual fun createCrashReporter(): CrashReporter = NoOpCrashReporter
