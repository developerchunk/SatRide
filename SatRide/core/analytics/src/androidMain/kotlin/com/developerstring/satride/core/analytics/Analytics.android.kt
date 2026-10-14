package com.developerstring.satride.core.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

internal actual fun createAnalytics(): Analytics = FirebaseAnalyticsReporter()

internal actual fun createCrashReporter(): CrashReporter = FirebaseCrashReporter()

/**
 * Firebase Analytics.
 *
 * `FirebaseAnalytics.getInstance` is resolved lazily rather than in the constructor: Koin builds
 * this singleton while the graph is starting, which on a cold launch can be before Firebase's
 * auto-init ContentProvider has run. Touching it too early throws, and it would throw during
 * startup — the one place a crash is least recoverable and hardest to diagnose.
 */
private class FirebaseAnalyticsReporter : Analytics {

    private val firebase by lazy { FirebaseAnalytics.getInstance(FirebaseContext.get()) }

    // The plain Bundle overload rather than the `ktx` builder: the KTX extensions were folded into
    // the main artifact and the old `analytics.ktx` package no longer exists, so this is the form
    // that does not move between Firebase versions.
    override fun log(event: SkyEvent, params: Map<String, String>) {
        firebase.logEvent(event.key, params.toBundle())
    }

    override fun screen(name: String) {
        firebase.logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            Bundle().apply { putString(FirebaseAnalytics.Param.SCREEN_NAME, name) },
        )
    }

    override fun property(name: String, value: String) {
        firebase.setUserProperty(name, value)
    }
}

private fun Map<String, String>.toBundle(): Bundle = Bundle(size).also { bundle ->
    forEach { (key, value) -> bundle.putString(key, value) }
}

private class FirebaseCrashReporter : CrashReporter {

    private val crashlytics by lazy { FirebaseCrashlytics.getInstance() }

    override fun log(message: String) = crashlytics.log(message)

    override fun setKey(key: String, value: String) = crashlytics.setCustomKey(key, value)

    override fun recordNonFatal(error: Throwable) = crashlytics.recordException(error)

    override fun setUserId(id: String?) = crashlytics.setUserId(id.orEmpty())
}

/**
 * The application context, stashed by the Android app at startup.
 *
 * `FirebaseAnalytics.getInstance` needs a Context, and this module is resolved from common code
 * that has none to give it. Taking a Koin-injected Context instead would make the common
 * [analyticsModule] Android-shaped, which is exactly what the seam exists to avoid.
 */
object FirebaseContext {
    private var context: android.content.Context? = null

    fun install(applicationContext: android.content.Context) {
        context = applicationContext.applicationContext
    }

    internal fun get(): android.content.Context =
        requireNotNull(context) { "FirebaseContext.install(...) must run before analytics is used" }
}
