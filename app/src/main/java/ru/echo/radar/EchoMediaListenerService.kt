package ru.echo.radar

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

class EchoMediaListenerService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private val controllerCallbacks = ConcurrentHashMap<MediaController, MediaController.Callback>()

    @Volatile
    private var lastReportedArtist: String = ""

    @Volatile
    private var lastReportedTitle: String = ""

    @Volatile
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
        } catch (_: Exception) {
        }
    }

    private fun setupMediaSessionMonitoring() {
        try {
            sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(this, EchoMediaListenerService::class.java)
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            val initial = sessionManager?.getActiveSessions(component)
            bindControllers(initial)
        } catch (_: Exception) {
            scanCurrentNotifications()
        }
    }

    private fun bindControllers(controllers: List<MediaController>?) {
        unbindAllControllers()
        if (controllers.isNullOrEmpty()) {
            scanCurrentNotifications()
            return
        }

        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    readControllerMetadata(controller)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (isPlaybackActive(state)) {
                        readControllerMetadata(controller)
                    }
                }

                override fun onSessionDestroyed() {
                    try {
                        controller.unregisterCallback(this)
                    } catch (_: Exception) {
                    }
                    controllerCallbacks.remove(controller)
                }
            }
            try {
                controller.registerCallback(callback)
                controllerCallbacks[controller] = callback
                readControllerMetadata(controller)
            } catch (_: Exception) {
            }
        }
    }

    private fun unbindAllControllers() {
        for ((ctrl, cb) in controllerCallbacks.entries) {
            try {
                ctrl.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        controllerCallbacks.clear()
    }

    private fun isPlaybackActive(state: PlaybackState?): Boolean {
        if (state == null) return false
        return state.state == PlaybackState.STATE_PLAYING ||
               state.state == PlaybackState.STATE_FAST_FORWARDING ||
               state.state == PlaybackState.STATE_REWINDING
    }

    private fun readControllerMetadata(controller: MediaController): Boolean {
        try {
            if (!isPlaybackActive(controller.playbackState)) return false

            val meta = controller.metadata ?: return false
            val rawArtist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: ""

            val rawTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: ""

            val pkg = controller.packageName ?: ""
            val artist = cleanMetadataString(rawArtist)
            val title = cleanMetadataString(rawTitle)

            if (artist.isNotBlank() && title.isNotBlank() && !isIgnoredContent(artist, title)) {
                publishTrackIfChanged(artist, title, pkg)
                return true
            }
        } catch (_: Exception) {
        }
        return false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        extractTrackFromNotification(sbn)
    }

    private fun scanCurrentNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                extractTrackFromNotification(sbn)
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun extractTrackFromNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: ""
        if (pkg == applicationContext.packageName) return

        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        // 1. Попытка прямого чтения через токен MediaSession внутри уведомления
        try {
            val token: MediaSession.Token? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }

            if (token != null) {
                val controller = MediaController(applicationContext, token)
                val state = controller.playbackState
                // Если сессия привязана, но стоит на паузе — игнорируем обновление шторки
                if (state != null && !isPlaybackActive(state)) {
                    return
                }
                if (readControllerMetadata(controller)) {
                    return
                }
            }
        } catch (_: Exception) {
        }

        // 2. Резервный парсинг для кастомных плееров без активного MediaController
        val isMusicCategory = n.category == Notification.CATEGORY_TRANSPORT
        val isKnownApp = isKnownMusicApp(pkg)
        if (!isMusicCategory && !isKnownApp) return

        // Игнорируем смахиваемые (неиграющие) уведомления от сторонних приложений
        if (!sbn.isOngoing && !isMusicCategory) return

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        if (rawTitle.isBlank() && rawText.isBlank()) return

        var artist = ""
        var title = ""

        if (rawTitle.isNotBlank() && rawText.isNotBlank()) {
            title = rawTitle
            artist = rawText
        } else {
            val full = if (rawTitle.isNotBlank()) rawTitle else rawText
            val parts = full.split(" — ", " - ", " – ", " · ")
            if (parts.size >= 2) {
                artist = parts[0].trim()
                title = parts.subList(1, parts.size).joinToString(" - ").trim()
            } else if (rawSubText.isNotBlank()) {
                title = full
                artist = rawSubText
            } else {
                return
            }
        }

        val cleanArtist = cleanMetadataString(artist)
        val cleanTitle = cleanMetadataString(title)

        if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank() && !isIgnoredContent(cleanArtist, cleanTitle)) {
            publishTrackIfChanged(cleanArtist, cleanTitle, pkg)
        }
    }

    private fun cleanMetadataString(input: String): String {
        return input
            .replace(Regex("\\s*\\[(Explicit|Clean|Official[^]]*)]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\((Official\\s+Video|Official\\s+Audio|Lyrics|Audio)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\.(mp3|flac|wav|m4a|ogg)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun isIgnoredContent(artist: String, title: String): Boolean {
        val combined = "$artist $title".lowercase()
        val stopPhrases = listOf(
            "реклама",
            "advertisement",
            "голосовое сообщение",
            "аудиосообщение",
            "voice message",
            "загрузка...",
            "loading...",
            "буферизация",
            "неизвестный трек"
        )
        return stopPhrases.any { combined.contains(it) }
    }

    private fun publishTrackIfChanged(artist: String, title: String, sourcePackage: String) {
        val now = System.currentTimeMillis()
        if (artist.equals(lastReportedArtist, ignoreCase = true) &&
            title.equals(lastReportedTitle, ignoreCase = true) &&
            (now - lastReportedTimeMs < 3000L)
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
               lower.contains("vk.admin") ||
               lower.contains("vkontakte") ||
               lower.contains("zvuk") ||
               lower.contains("sber") ||
               lower.contains("spotify") ||
               lower.contains("deezer") ||
               lower.contains("soundcloud") ||
               lower.contains("aimp") ||
               lower.contains("poweramp") ||
               lower.contains("player")
    }
}
