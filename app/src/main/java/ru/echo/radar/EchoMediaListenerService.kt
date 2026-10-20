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

class EchoMediaListenerService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private val activeControllers = mutableListOf<MediaController>()

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
        clearControllers()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        EchoStateRepository.setListenerConnected(true)
        if (sbn != null) {
            extractTrackFromNotification(sbn)
        }
        refreshSessionsSafe()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        refreshSessionsSafe()
    }

    private fun setupMediaSessionMonitoring() {
        try {
            val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            sessionManager = mgr
            val component = ComponentName(this, EchoMediaListenerService::class.java)
            mgr.addOnActiveSessionsChangedListener(sessionListener, component)
            bindControllers(mgr.getActiveSessions(component))
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun refreshSessionsSafe() {
        try {
            val component = ComponentName(this, EchoMediaListenerService::class.java)
            val controllers = sessionManager?.getActiveSessions(component)
            if (!controllers.isNullOrEmpty()) {
                bindControllers(controllers)
            }
        } catch (_: Exception) {
        }
    }

    private fun scanCurrentNotifications() {
        try {
            val list = activeNotifications ?: return
            for (sbn in list) {
                extractTrackFromNotification(sbn)
            }
        } catch (_: Exception) {
        }
    }

    private fun extractTrackFromNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg.contains("systemui")) return

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        // 1. Проверяем токен медиа-сессии внутри уведомления
        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }

        if (token != null) {
            try {
                val controller = MediaController(this, token)
                if (readControllerMetadata(controller)) return
            } catch (_: Exception) {
            }
        }

        // 2. Прямое чтение заголовка и текста для медиа-уведомлений
        val category = notif.category
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val isMediaStyle = category == Notification.CATEGORY_TRANSPORT ||
            template.contains("MediaStyle", ignoreCase = true) ||
            isKnownMusicApp(pkg)

        if (!isMediaStyle) return

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val rawSub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        if (rawTitle.isNotEmpty() && rawText.isNotEmpty()) {
            EchoStateRepository.updateMyPlayingTrack(
                artist = rawText,
                title = rawTitle,
                sourcePackage = pkg
            )
        } else if (rawTitle.contains("—") || rawTitle.contains("-")) {
            val parts = rawTitle.split("—", "-", limit = 2)
            if (parts.size == 2) {
                EchoStateRepository.updateMyPlayingTrack(
                    artist = parts[0].trim(),
                    title = parts[1].trim(),
                    sourcePackage = pkg
                )
            }
        } else if (rawTitle.isNotEmpty() && rawSub.isNotEmpty()) {
            EchoStateRepository.updateMyPlayingTrack(
                artist = rawSub,
                title = rawTitle,
                sourcePackage = pkg
            )
        }
    }

    private fun isKnownMusicApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("music") || p.contains("yandex") || p.contains("vkontakte") ||
            p.contains("zvuk") || p.contains("sber") || p.contains("spotify") ||
            p.contains("poweramp") || p.contains("aimp") || p.contains("player") ||
            p.contains("youtube") || p.contains("audio")
    }

    private fun bindControllers(controllers: List<MediaController>?) {
        clearControllers()
        if (controllers.isNullOrEmpty()) return

        for (controller in controllers) {
            activeControllers.add(controller)
            controller.registerCallback(controllerCallback)
            readControllerMetadata(controller)
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshSessionsSafe()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshSessionsSafe()
        }
    }

    private fun readControllerMetadata(controller: MediaController): Boolean {
        val metadata = controller.metadata ?: return false
        val state = controller.playbackState?.state

        val isActive = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == null

        if (!isActive) return false

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: ""

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""

        if (title.isNotBlank() || artist.isNotBlank()) {
            EchoStateRepository.updateMyPlayingTrack(
                artist = artist.ifBlank { "Неизвестный артист" },
                title = title.ifBlank { "Неизвестный трек" },
                sourcePackage = controller.packageName ?: "system"
            )
            return true
        }
        return false
    }

    private fun clearControllers() {
        for (c in activeControllers) {
            try {
                c.unregisterCallback(controllerCallback)
            } catch (_: Exception) {
            }
        }
        activeControllers.clear()
    }
}
