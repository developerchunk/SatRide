package com.developerstring.satride.data

import android.content.Context
import com.developerstring.satride.feature.play.EarthTextureSource
import org.koin.core.module.Module
import org.koin.dsl.module

/** Reads the Earth textures from the APK's assets, where the build copied them. */
private class AndroidAssetTextureSource(private val context: Context) : EarthTextureSource {
    override suspend fun read(fileName: String): ByteArray? = runCatching {
        context.assets.open(fileName).use { it.readBytes() }
    }.getOrNull()
}

actual fun playAssetsModule(): Module = module {
    single<EarthTextureSource> { AndroidAssetTextureSource(get<Context>()) }
}
