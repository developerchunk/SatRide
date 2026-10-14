package com.developerstring.satride.core.notifications

import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File

/** Shows one notification. Separated from the scheduler so the timing can be tested without a desktop. */
internal interface DesktopNotifier {
    val isAvailable: Boolean

    fun notify(title: String, body: String)
}

/**
 * The host's own notification centre, reached the way each desktop expects.
 *
 * - **macOS**: `osascript … display notification`. Notification Center delivers it with the
 *   system sound and it lands in the notification list like any other.
 * - **Linux**: `notify-send`, present on every mainstream desktop through libnotify.
 * - **Otherwise** (Windows, or a Linux box without libnotify): a tray icon's balloon, which is the
 *   only notification API the JDK itself has. The icon is added lazily, on the first alert, so an
 *   app that never arms a reminder never puts anything in the tray.
 *
 * No network, no third-party library: three commands the machine already has.
 */
internal class SystemNotifier(
    private val os: String = System.getProperty("os.name").orEmpty(),
    private val path: String = System.getenv("PATH").orEmpty(),
    private val run: (List<String>) -> Unit = { command -> ProcessBuilder(command).start() },
) : DesktopNotifier {

    private val isMac = os.startsWith("Mac", ignoreCase = true)
    private val notifySend: String? by lazy { findOnPath("notify-send") }

    override val isAvailable: Boolean
        get() = isMac || notifySend != null || SystemTray.isSupported()

    override fun notify(title: String, body: String) {
        when {
            isMac -> run(
                listOf(
                    "osascript",
                    "-e",
                    "display notification \"${appleScriptString(
                        body,
                    )}\" with title \"${appleScriptString(title)}\" sound name \"default\"",
                ),
            )

            notifySend != null -> run(listOf(notifySend!!, "--app-name=SatRide", title, body))

            else -> tray().displayMessage(title, body, TrayIcon.MessageType.INFO)
        }
    }

    private var trayIcon: TrayIcon? = null

    @Synchronized
    private fun tray(): TrayIcon = trayIcon ?: run {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also {
            val g = it.createGraphics()
            g.color = Color(0x47, 0xC2, 0xFF)
            g.fillOval(1, 1, 14, 14)
            g.dispose()
        }
        TrayIcon(image, "SatRide").apply {
            isImageAutoSize = true
            SystemTray.getSystemTray().add(this)
        }.also { trayIcon = it }
    }

    private fun findOnPath(name: String): String? = path.split(File.pathSeparator).asSequence()
        .map { File(it, name) }
        .firstOrNull { it.canExecute() }
        ?.absolutePath

    private companion object {
        /** AppleScript string literals escape exactly two characters. */
        fun appleScriptString(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
