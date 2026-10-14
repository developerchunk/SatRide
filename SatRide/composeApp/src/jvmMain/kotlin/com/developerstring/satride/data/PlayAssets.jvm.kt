package com.developerstring.satride.data

import com.developerstring.satride.feature.play.EarthTextureSource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.core.module.Module
import org.koin.dsl.module
import satride.composeapp.generated.resources.Res

private class ComposeResourceTextureSource : EarthTextureSource {
    @OptIn(ExperimentalResourceApi::class)
    override suspend fun read(fileName: String): ByteArray? = runCatching {
        Res.readBytes("files/$fileName")
    }.getOrNull()
}

actual fun playAssetsModule(): Module = module {
    single<EarthTextureSource> { ComposeResourceTextureSource() }
}
