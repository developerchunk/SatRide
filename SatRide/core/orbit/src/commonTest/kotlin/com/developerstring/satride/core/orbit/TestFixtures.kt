package com.developerstring.satride.core.orbit

import com.developerstring.satride.testfixtures.FIXTURE_DIR
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Reads the reference data files in `src/commonTest/resources`.
 *
 * commonTest has no portable resource loader, so the directory's absolute path is generated
 * into [FIXTURE_DIR] at build time and read through okio, which has a real filesystem on both
 * the JVM and Kotlin/Native. Keeping the fixtures as files rather than embedded strings also
 * sidesteps the JVM's 64 KB limit on string constants — `tcppver.out` alone is 140 KB.
 */
internal object TestFixtures {

    fun read(name: String): String {
        val path = "$FIXTURE_DIR/$name".toPath()
        return FileSystem.SYSTEM.read(path) { readUtf8() }
    }

    fun lines(name: String): List<String> = read(name).lines()
}
