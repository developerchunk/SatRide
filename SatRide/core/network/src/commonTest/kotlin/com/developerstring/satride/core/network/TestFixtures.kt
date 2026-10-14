package com.developerstring.satride.core.network

import com.developerstring.satride.testfixtures.FIXTURE_DIR
import okio.FileSystem
import okio.Path.Companion.toPath

/** Reads the recorded API responses in `src/commonTest/resources` (see FIXTURES.md). */
internal object TestFixtures {
    fun read(name: String): String = FileSystem.SYSTEM.read("$FIXTURE_DIR/$name".toPath()) { readUtf8() }
}
