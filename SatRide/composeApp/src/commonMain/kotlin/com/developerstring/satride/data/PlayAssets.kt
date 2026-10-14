package com.developerstring.satride.data

import org.koin.core.module.Module

/**
 * Supplies the Play tab's Earth textures, per platform.
 *
 * Same shape and same reason as [catalogSnapshotModule]: the textures live once in
 * `composeApp/src/commonMain/composeResources/files`, Compose Resources carries them on iOS and
 * desktop, and the Android build copies them into APK assets because Compose Resources does not
 * package commonMain files for the Android target under the AGP KMP plugin.
 */
expect fun playAssetsModule(): Module
