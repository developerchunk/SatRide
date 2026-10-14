package com.developerstring.satride.data

import org.koin.core.module.Module

/**
 * Supplies the bundled catalog, per platform.
 *
 * Deliberately **not** Compose Resources, even though the file is bundled with the app and Compose
 * Resources is the obvious place to put it. With the AGP Kotlin-Multiplatform library plugin,
 * Compose Resources does not fold `commonMain` resources into the Android assets: iOS and desktop
 * aggregate them correctly, Android silently ships without them. The result was an app that lost
 * its offline catalog on one platform only — invisible in every test, and only found by watching
 * the log on a real phone.
 *
 * This is also the better fit on its own merits. The snapshot is data, not a UI asset; loading it
 * through each platform's own mechanism is one small implementation per target instead of a
 * dependency on UI-toolkit plumbing behaving identically everywhere.
 */
expect fun catalogSnapshotModule(): Module

internal const val CATALOG_SNAPSHOT_FILE: String = "catalog_snapshot.json"
