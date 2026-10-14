package com.developerstring.satride.di

import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the dependency graph: a module that cannot start, or a definition that cannot be
 * resolved, fails here rather than at app launch on a device.
 *
 * Runs on every target, so it also proves Koin initialises identically on JVM and iOS.
 */
class AppModuleTest {
    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun graphStartsAndResolvesAppInfo() {
        val koin = startKoin { modules(satRideModules()) }.koin

        val appInfo = koin.getOrNull<AppInfo>()

        assertNotNull(appInfo, "AppInfo must resolve — DI did not reach the app shell")
        assertEquals("SatRide", appInfo.name)
    }

    @Test
    fun everyModuleIsListedExactlyOnce() {
        val modules = satRideModules()

        assertTrue(modules.isNotEmpty(), "Koin graph must not be empty")
        assertEquals(
            modules.size,
            modules.distinct().size,
            "a module is registered twice in satRideModules()",
        )
    }
}
