package com.developerstring.satride.ui

/**
 * True where the bottom tab bar is rendered by the platform shell rather than by Compose.
 *
 * On iOS the bar is a native SwiftUI Liquid Glass view (`iosApp/.../ContentView.swift`); on Android
 * and desktop Compose draws its own floating `GlassTabBar`. Liquid Glass has to live in the native
 * view hierarchy to sample the content behind it (a `UIKitView` embedded in Compose cannot see the
 * Skiko-rendered content), so on iOS the bar moves out of Compose entirely.
 *
 * In both cases the bar floats *over* full-screen content — `RootScaffold` no longer reserves any
 * space for it; it just overlays the bar on top.
 */
internal expect val nativeBottomBarActive: Boolean
