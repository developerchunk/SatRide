package com.developerstring.satride.core.analytics

/**
 * iOS reports nothing yet.
 *
 * The KMP Firebase wrapper this project uses covers auth and Firestore but publishes no Analytics
 * or Crashlytics bindings, and reaching the native SDKs would mean adding them to the Xcode project
 * and bridging by hand. That is real work with no benefit until the iOS build actually ships, so it
 * is deferred rather than half-done — every call site already reports through the seam, so wiring
 * it later touches this file and nothing else.
 */
internal actual fun createAnalytics(): Analytics = NoOpAnalytics

internal actual fun createCrashReporter(): CrashReporter = NoOpCrashReporter
