package com.developerstring.satride.data

import com.developerstring.satride.core.data.CatalogSnapshotSource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.core.module.Module
import org.koin.dsl.module
import satride.composeapp.generated.resources.Res

private class ComposeResourceSnapshotSource : CatalogSnapshotSource {
    @OptIn(ExperimentalResourceApi::class)
    override suspend fun read(): String? = runCatching {
        Res.readBytes("files/$CATALOG_SNAPSHOT_FILE").decodeToString()
    }.getOrNull()
}

actual fun catalogSnapshotModule(): Module = module {
    single<CatalogSnapshotSource> { ComposeResourceSnapshotSource() }
}
