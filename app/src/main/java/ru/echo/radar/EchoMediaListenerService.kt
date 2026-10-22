package ru.echo.radar

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class EchoMediaListenerService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private val activeControllers = mutableListOf<MediaController>()
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private var lastReportedArtist: String = ""
    private var lastReportedTitle: String = ""
    private var lastReportedTimeMs: Long = 0L

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindControllers(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        EchoStateRepository.setListenerConnected(true)
        setupMediaSessionMonitoring()
        scanCurrentNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        EchoStateRepository.setListenerConnected(false)
        unbindAllControllers()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindAllControllers()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
    }

    private fun setupMediaSessionMonitoring() {
        try {
            sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(this, EchoMediaListenerService::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            val initial = sessionManager?.getActiveSessions(component)
            bindControllers(initial)
        } catch (_: Exception) {
            // Резервный режим через сканирование шторки уведомлений
            scanCurrentNotifications()
        }
    }

    private fun bindControllers(controllers: List<MediaController>?) {
        unbindAllControllers()
        if (controllers.isNullOrEmpty()) {
            scanCurrentNotifications()
            return
        }

        activeControllers.addAll(controllers)
        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    readControllerMetadata(controller)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == PlaybackState.STATE_PLAYING) {
                        readControllerMetadata(controller)
                    }
                }
            }
            try {
                controller.registerCallback(callback)
                controllerCallbacks[controller] = callback
                readControllerMetadata(controller)
            } catch (_: Exception) {}
        }
    }

    private fun unbindAllControllers() {
        for ((ctrl, cb) in controllerCallbacks) {
            try {
                ctrl.unregisterCallback(cb)
            } catch (_: Exception) {}
        }
        controllerCallbacks.clear()
        activeControllers.clear()
    }

    private fun readControllerMetadata(controller: MediaController) {
        try {
            val state = controller.playbackState
            // Проверяем, что музыка действительно играет или буферизуется
            val isPlaying = state != null && (
                state.state == PlaybackState.STATE_PLAYING ||
                state.state == PlaybackState.STATE_FAST_FORWARDING ||
                state.state == PlaybackState.STATE_REWINDING
            )

            val meta = controller.metadata ?: return
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: ""

            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val pkg = controller.packageName ?: ""

            if (isPlaying && artist.isNotBlank() && title.isNotBlank()) {
                publishTrackIfChanged(artist.trim(), title.trim(), pkg)
            }
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        extractTrackFromNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun scanCurrentNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                extractTrackFromNotification(sbn)
            }
        } catch (_: Exception) {}
    }

    private fun extractTrackFromNotification(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val pkg = sbn.packageName ?: ""

        val isMusicCategory = n.category == Notification.CATEGORY_TRANSPORT
        val hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val isKnownApp = isKnownMusicApp(pkg)

        if (!isMusicCategory && !hasMediaSession && !isKnownApp) {
            return
        }

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (rawTitle.isBlank() && rawText.isBlank()) return

        var artist = ""
        var title = ""

        if (rawTitle.isNotBlank() && rawText.isNotBlank()) {
            // В большинстве плееров (Яндекс Музыка, VK): EXTRA_TITLE = Название трека, EXTRA_TEXT = Исполнитель
            title = rawTitle
            artist = rawText
        } else {
            val full = if (rawTitle.isNotBlank()) rawTitle else rawText
            val parts = full.split(" - ", " — ", " · ")
            if (parts.size >= 2) {
                artist = parts[0].trim()
                title = parts[1].trim()
            } else {
                title = full
                artist = "Неизвестный артист"
            }
        }

        if (artist.isNotBlank() && title.isNotBlank()) {
            publishTrackIfChanged(artist, title, pkg)
        }
    }

    private fun publishTrackIfChanged(artist: String, title: String, sourcePackage: String) {
        val now = System.currentTimeMillis()
        // Дедупликация: предотвращаем отправку одинаковых треков чаще одного раза в 2.5 секунды
        if (artist.equals(lastReportedArtist, ignoreCase = true) &&
            title.equals(lastReportedTitle, ignoreCase = true) &&
            (now - lastReportedTimeMs < 2500)
        ) {
            return
        }

        lastReportedArtist = artist
        lastReportedTitle = title
        lastReportedTimeMs = now

        EchoStateRepository.updateMyPlayingTrack(
            artist = artist,
            title = title,
            sourcePackage = sourcePackage
        )
    }

    private fun isKnownMusicApp(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("music") ||
               lower.contains("audio") ||
               lower.contains("yandex") ||
               lower.contains("vk") ||
               lower.contains("zvuk") ||
               lower.contains("sber") ||
               lower.contains("spotify") ||
               lower.contains("deezer") ||
               lower.contains("player")
    }
}
