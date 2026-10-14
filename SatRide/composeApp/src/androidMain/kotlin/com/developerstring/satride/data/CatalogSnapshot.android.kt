package com.developerstring.satride.data

import android.content.Context
import com.developerstring.satride.core.data.CatalogSnapshotSource
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Reads the snapshot from the APK's assets.
 *
 * The file is copied into `androidApp`'s assets at build time from the single copy in
 * `composeApp/src/commonMain/composeResources/files`, so there is one file in version control and
 * no risk of the two drifting.
 */
private class AndroidAssetSnapshotSource(private val context: Context) : CatalogSnapshotSource {
    override suspend fun read(): String? = runCatching {
        context.assets.open(CATALOG_SNAPSHOT_FILE).use { it.readBytes().decodeToString() }
    }.getOrNull()
}

actual fun catalogSnapshotModule(): Module = module {
    single<CatalogSnapshotSource> { AndroidAssetSnapshotSource(get<Context>()) }
}
