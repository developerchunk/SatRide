package com.developerstring.satride.core.auth

import android.app.Application
import co.touchlab.kermit.Logger
import com.google.firebase.FirebasePlatform
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties

/**
 * What a desktop process needs to join the same Firebase project the phones use.
 *
 * On Android these values arrive through the google-services Gradle plugin and on iOS through
 * `GoogleService-Info.plist`; a JVM process has neither, so they are read out of the very same
 * `google-services.json` at launch. One source of truth, three platforms.
 */
data class DesktopFirebaseConfig(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val storageBucket: String? = null,
    val gcmSenderId: String? = null,
) {
    companion object {
        /**
         * Parses the Android `google-services.json`.
         *
         * The first registered client is used. Every client in the file shares the project, the
         * API key and the sender id — the per-client part is the Android package name, which a
         * desktop process has no use for — so which one is picked does not matter to Auth or
         * Firestore.
         */
        fun fromGoogleServicesJson(text: String): DesktopFirebaseConfig? = runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val projectInfo = root.getValue("project_info").jsonObject
            val client = root.getValue("client").jsonArray.first().jsonObject
            DesktopFirebaseConfig(
                projectId = projectInfo.getValue("project_id").jsonPrimitive.content,
                applicationId = client.getValue("client_info").jsonObject
                    .getValue("mobilesdk_app_id").jsonPrimitive.content,
                apiKey = client.getValue("api_key").jsonArray.first().jsonObject
                    .getValue("current_key").jsonPrimitive.content,
                storageBucket = projectInfo["storage_bucket"]?.jsonPrimitive?.content,
                gcmSenderId = projectInfo["project_number"]?.jsonPrimitive?.content,
            )
        }.getOrNull()
    }
}

/**
 * Starts Firebase for the desktop build.
 *
 * Must run **before** Koin, because `authModule` decides between the real and the unavailable
 * repository at construction by asking whether `Firebase.auth` resolves.
 *
 * GitLive's JVM artifacts sit on a Java port of the Android SDK that has no storage of its own:
 * it hands every value it wants kept — the signed-in user's refresh token, above all — to a
 * [FirebasePlatform] the host supplies. Ours is a properties file in the app's data directory with
 * owner-only permissions, which is what makes a desktop sign-in survive a relaunch instead of
 * asking for the account again every time the window opens.
 */
object DesktopFirebase {

    private val logger = Logger.withTag("DesktopFirebase")

    @Volatile
    var isInitialized: Boolean = false
        private set

    /**
     * @param storageDir where the session file lives; created if missing.
     * @return true when Firebase is ready. False is an expected outcome — a checkout with no
     *   `google-services.json` — and the app then runs signed out, exactly as it did before.
     */
    @Synchronized
    fun initialize(config: DesktopFirebaseConfig, storageDir: File): Boolean {
        if (isInitialized) return true
        return runCatching {
            storageDir.mkdirs()
            FirebasePlatform.initializeFirebasePlatform(FileBackedFirebasePlatform(File(storageDir, SESSION_FILE)))
            Firebase.initialize(
                context = Application(),
                options = FirebaseOptions(
                    applicationId = config.applicationId,
                    apiKey = config.apiKey,
                    projectId = config.projectId,
                    storageBucket = config.storageBucket,
                    gcmSenderId = config.gcmSenderId,
                ),
            )
            isInitialized = true
            logger.i { "Firebase ready for project ${config.projectId}" }
            true
        }.getOrElse {
            logger.w(it) { "Firebase could not start; sign-in disabled in this build" }
            false
        }
    }

    private const val SESSION_FILE = "firebase-session.properties"
}

/**
 * The Java SDK's key-value store, kept in one properties file.
 *
 * Written through on every change rather than on exit, because a desktop process is far more
 * likely to be killed than to quit cleanly — the window closed from the Dock, a laptop lid shut —
 * and losing the session that way would look like the app forgot the user.
 */
private class FileBackedFirebasePlatform(private val file: File) : FirebasePlatform() {

    private val properties = Properties().apply {
        if (file.isFile) {
            runCatching { file.inputStream().use { load(it) } }
        }
    }

    @Synchronized
    override fun store(key: String, value: String) {
        properties.setProperty(key, value)
        persist()
    }

    @Synchronized
    override fun retrieve(key: String): String? = properties.getProperty(key)

    @Synchronized
    override fun clear(key: String) {
        properties.remove(key)
        persist()
    }

    override fun log(msg: String) {
        Logger.withTag("Firebase").d { msg }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { properties.store(it, "SatRide desktop session - do not share") }
            // The file holds a refresh token. Owner-only where the filesystem can express it;
            // silently best-effort on Windows, where POSIX bits do not exist.
            runCatching {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
        }.onFailure { Logger.withTag("DesktopFirebase").w(it) { "could not persist the session" } }
    }
}
