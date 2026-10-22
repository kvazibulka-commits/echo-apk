package ru.echo.radar

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class EchoTrackSignal(
    val id: String,
    val artist: String,
    val title: String,
    val sourceName: String = "ЭХО",
    val distanceMeters: Int = 45,
    val angleDegrees: Float = 0f,
    val distanceRatio: Float = 0.45f,
    val personaTitle: String = "Архивариус забытых кассет",
    val personaObservation: String = "Слушает музыку в вечерней тишине.",
    val moodLabel: String = "Теплый винил",
    val compatibilityPercent: Int = 88,
    val resonanceDays: Int = 1,
    val encountersCount: Int = 1,
    val colorHex: Long = 0xFF38BDF8,
    val avatarPetals: Int = 6,
    val zoneName: String = "Вечерняя улица",
    val reactions: Map<String, Int> = emptyMap()
)

data class EchoTimeCapsule(
    val id: String,
    val creatorId: String,
    val artist: String,
    val title: String,
    val moodLabel: String,
    val personaTitle: String,
    val zoneName: String,
    val secretMessage: String,
    val distanceMeters: Int = 60,
    val angleDegrees: Float = 120f,
    val distanceRatio: Float = 0.5f,
    val compatibilityPercent: Int = 90,
    val isUnlocked: Boolean = true
)

data class SavedEchoTrack(
    val artist: String,
    val title: String,
    val personaTitle: String,
    val moodLabel: String,
    val savedAt: String
)

data class IncomingReactionBanner(
    val id: Long,
    val symbol: String,
    val message: String
)

data class CityZoneTrace(
    val placeName: String,
    val dominantVibe: String,
    val recentArtists: String,
    val listenersCount: Int
)

data class CuratedPersona(
    val personaTitle: String,
    val moodLabel: String,
    val observation: String,
    val colorHex: Long,
    val petals: Int,
    val vibeVector: Int = 0
)

object EchoStateRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null

    var deviceId: String = "echo_android_init"
        private set

    var serverHost: String = "172.16.0.2:8080"
        private set

    private var lastBaseSourceName: String = "ЭХО"

    val availableZones = listOf(
        "Вечерняя улица",
        "Сретенка · Камерные бары",
        "Парк Горького · Набережная",
        "Покровка · Ночные кофейни",
        "Северный лесопарк"
    )

    private val _presenceEnabled = MutableStateFlow(true)
    val presenceEnabled: StateFlow<Boolean> = _presenceEnabled.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _myCurrentZone = MutableStateFlow("Вечерняя улица")
    val myCurrentZone: StateFlow<String> = _myCurrentZone.asStateFlow()

    private val _myCurrentTrack = MutableStateFlow(
        EchoTrackSignal(
            id = "my_self_signal",
            artist = "Sirotkin",
            title = "Август",
            sourceName = "ЭХО",
            distanceMeters = 0,
            angleDegrees = 0f,
            distanceRatio = 0f,
            personaTitle = "Архивариус забытых кассет",
            personaObservation = "Слушает теплые гитарные реверберации вечернего города.",
            moodLabel = "Теплый винил",
            compatibilityPercent = 100,
            colorHex = 0xFF38BDF8,
            avatarPetals = 6,
            zoneName = "Вечерняя улица"
        )
    )
    val myCurrentTrack: StateFlow<EchoTrackSignal> = _myCurrentTrack.asStateFlow()

    private val _nearbySignals = MutableStateFlow<List<EchoTrackSignal>>(emptyList())
    val nearbySignals: StateFlow<List<EchoTrackSignal>> = _nearbySignals.asStateFlow()

    private val _nearbyCapsules = MutableStateFlow<List<EchoTimeCapsule>>(emptyList())
    val nearbyCapsules: StateFlow<List<EchoTimeCapsule>> = _nearbyCapsules.asStateFlow()

    private val _savedTracks = MutableStateFlow<List<SavedEchoTrack>>(emptyList())
    val savedTracks: StateFlow<List<SavedEchoTrack>> = _savedTracks.asStateFlow()

    private val _incomingBanner = MutableStateFlow<IncomingReactionBanner?>(null)
    val incomingBanner: StateFlow<IncomingReactionBanner?> = _incomingBanner.asStateFlow()

    private val _eveningTraces = MutableStateFlow<List<String>>(emptyList())
    val eveningTraces: StateFlow<List<String>> = _eveningTraces.asStateFlow()

    private val _liveCityZones = MutableStateFlow<List<CityZoneTrace>>(emptyList())
    val liveCityZones: StateFlow<List<CityZoneTrace>> = _liveCityZones.asStateFlow()

    private val localHistoryQueue = mutableListOf<String>()

    fun initLocalStore(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("echo_radar_prefs", Context.MODE_PRIVATE)
        prefs = p

        var savedDevId = p.getString("device_id", "") ?: ""
        if (savedDevId.isBlank()) {
            savedDevId = "echo_android_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6)
            p.edit().putString("device_id", savedDevId).apply()
        }
        deviceId = savedDevId

        val savedIp = p.getString("server_ip", "") ?: ""
        if (savedIp.isNotBlank()) {
            serverHost = savedIp
        }

        val savedZone = p.getString("my_zone", "Вечерняя улица") ?: "Вечерняя улица"
        _myCurrentZone.value = savedZone

        loadSavedTracksFromPrefs(p)
        startUdpDiscovery()
        startServerSyncLoop()
    }

    fun togglePresence() {
        _presenceEnabled.value = !_presenceEnabled.value
    }

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun setMyZone(zone: String) {
        _myCurrentZone.value = zone
        prefs?.edit()?.putString("my_zone", zone)?.apply()
    }

    fun setCustomServerIp(ip: String) {
        serverHost = ip.trim()
        prefs?.edit()?.putString("server_ip", serverHost)?.apply()
    }

    fun dismissBanner() {
        _incomingBanner.value = null
    }

    fun updateDetectedMusic(artist: String, title: String, sourcePackage: String) {
        updateMyPlayingTrack(artist = artist, title = title, sourcePackage = sourcePackage)
    }

    fun updateMyPlayingTrack(
        artist: String,
        title: String,
        sourcePackage: String = "",
        sourceName: String = ""
    ) {
        val cleanArtist = artist.trim().ifEmpty { "Неизвестный артист" }
        val cleanTitle = title.trim().ifEmpty { "Без названия" }

        val rawSource = when {
            sourceName.isNotBlank() && !sourceName.contains(".") -> sourceName
            sourcePackage.isNotBlank() -> sourcePackage
            sourceName.isNotBlank() -> sourceName
            else -> "Плеер"
        }
        val cleanSourceName = when {
            rawSource.contains("yandex", ignoreCase = true) -> "Яндекс Музыка"
            rawSource.contains("vk", ignoreCase = true) -> "VK Музыка"
            rawSource.contains("zvuk", ignoreCase = true) || rawSource.contains("sber", ignoreCase = true) -> "Звук"
            rawSource.contains("spotify", ignoreCase = true) -> "Spotify"
            rawSource.contains(".") -> "Плеер"
            else -> rawSource
        }
        lastBaseSourceName = cleanSourceName

        val cur = _myCurrentTrack.value
        if (cur.artist.equals(cleanArtist, ignoreCase = true) && cur.title.equals(cleanTitle, ignoreCase = true)) {
            return
        }

        val record = "$cleanArtist — $cleanTitle"
        synchronized(localHistoryQueue) {
            if (localHistoryQueue.lastOrNull() != record) {
                localHistoryQueue.add(record)
                if (localHistoryQueue.size > 8) {
                    localHistoryQueue.removeAt(0)
                }
            }
        }

        _myCurrentTrack.value = cur.copy(
            artist = cleanArtist,
            title = cleanTitle,
            sourceName = "$cleanSourceName ●",
            zoneName = _myCurrentZone.value
        )

        scope.launch {
            sendSignalToServer()
        }
    }

    fun saveTrackToFinds(signal: EchoTrackSignal): Boolean {
        val currentList = _savedTracks.value.toMutableList()
        val exists = currentList.any {
            it.artist.equals(signal.artist, ignoreCase = true) &&
            it.title.equals(signal.title, ignoreCase = true)
        }
        if (exists) return false

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newTrack = SavedEchoTrack(
            artist = signal.artist,
            title = signal.title,
            personaTitle = signal.personaTitle,
            moodLabel = signal.moodLabel,
            savedAt = timeStr
        )
        currentList.add(0, newTrack)
        _savedTracks.value = currentList
        saveSavedTracksToPrefs()
        return true
    }

    fun removeSavedTrack(track: SavedEchoTrack) {
        val currentList = _savedTracks.value.toMutableList()
        currentList.removeAll { it.artist == track.artist && it.title == track.title }
        _savedTracks.value = currentList
        saveSavedTracksToPrefs()
    }

    fun sendSymbolReaction(targetId: String, symbol: String) {
        scope.launch {
            try {
                val url = URL("http://$serverHost/api/react")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 3000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = JSONObject().apply {
                    put("targetId", targetId)
                    put("symbol", symbol)
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    handleServerResponse(JSONObject(resp))
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun dropTimeCapsule(secretMessage: String) {
        val cur = _myCurrentTrack.value
        scope.launch {
            try {
                val url = URL("http://$serverHost/api/drop_capsule")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3500
                    readTimeout = 3500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = JSONObject().apply {
                    put("creatorId", deviceId)
                    put("artist", cur.artist)
                    put("title", cur.title)
                    put("moodLabel", cur.moodLabel)
                    put("personaTitle", cur.personaTitle)
                    put("zoneName", _myCurrentZone.value)
                    put("secretMessage", secretMessage)
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
                if (conn.responseCode == 200) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    handleServerResponse(JSONObject(resp))
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun exportEveningPlaylist(context: Context) {
        val tracks = _savedTracks.value
        if (tracks.isEmpty()) {
            Toast.makeText(context, "В ваших находках пока нет треков вечера", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder("✦ Плейлист моего вечера • ЭХО ✦\n\n")
        tracks.forEachIndexed { index, t ->
            sb.append("${index + 1}. ${t.artist} — ${t.title} (${t.personaTitle} • ${t.moodLabel})\n")
        }
        sb.append("\nСлушать в стриминге: https://music.yandex.ru")

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Экспорт плейлиста вечера")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    private fun startUdpDiscovery() {
        scope.launch {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(8888))
                    soTimeout = 4000
                }
                val buffer = ByteArray(256)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length).trim()
                        if (msg.startsWith("ECHO_SERVER_DISCOVERY:")) {
                            val portStr = msg.removePrefix("ECHO_SERVER_DISCOVERY:").trim()
                            val senderIp = packet.address.hostAddress
                            if (!senderIp.isNullOrBlank()) {
                                val discovered = "$senderIp:$portStr"
                                if (discovered != serverHost) {
                                    serverHost = discovered
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    delay(2000)
                }
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun startServerSyncLoop() {
        scope.launch {
            while (isActive) {
                if (_presenceEnabled.value) {
                    sendSignalToServer()
                }
                delay(2500)
            }
        }
    }

    private fun sendSignalToServer() {
        try {
            val cur = _myCurrentTrack.value
            val url = URL("http://$serverHost/api/signal")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val body = JSONObject().apply {
                put("id", deviceId)
                put("artist", cur.artist)
                put("title", cur.title)
                put("sourceName", cur.sourceName)
                put("zoneName", _myCurrentZone.value)
                put("personaTitle", cur.personaTitle)
                put("personaObservation", cur.personaObservation)
                put("moodLabel", cur.moodLabel)
                put("colorHex", cur.colorHex)
                put("avatarPetals", cur.avatarPetals)

                val histArr = JSONArray()
                synchronized(localHistoryQueue) {
                    localHistoryQueue.forEach { histArr.put(it) }
                }
                put("recentTracks", histArr)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            if (conn.responseCode == 200) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                handleServerResponse(JSONObject(resp))
            }
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun handleServerResponse(json: JSONObject) {
        val current = _myCurrentTrack.value

        // 1. ПЕРЕХВАТ РЕАКЦИЙ ДЛЯ ТЕКУЩЕГО УСТРОЙСТВА
        val myReactObj = json.optJSONObject("myReactions")
        val parsedMyReactions = mutableMapOf<String, Int>()
        if (myReactObj != null) {
            val keys = myReactObj.keys()
            while (keys.hasNext()) {
                val sym = keys.next()
                val newCount = myReactObj.optInt(sym, 0)
                parsedMyReactions[sym] = newCount
                val oldCount = current.reactions[sym] ?: 0
                if (newCount > oldCount) {
                    _incomingBanner.value = IncomingReactionBanner(
                        id = System.currentTimeMillis(),
                        symbol = sym,
                        message = "Слушатель рядом откликнулся на трек «${current.artist}» символом $sym"
                    )
                }
            }
        }

        // 2. ПРЯМАЯ ДОСТАВКА ИМПУЛЬСА (если сервер вернул активный incomingBanner)
        val bannerObj = json.optJSONObject("incomingBanner")
        if (bannerObj != null) {
            val targetId = bannerObj.optString("targetId", "")
            val bId = bannerObj.optLong("id", System.currentTimeMillis())
            val bSym = bannerObj.optString("symbol", "✦")
            val bMsg = bannerObj.optString("message", "Вам отправлен импульс $bSym")
            if (targetId.isEmpty() || targetId == deviceId) {
                if (_incomingBanner.value?.id != bId) {
                    _incomingBanner.value = IncomingReactionBanner(id = bId, symbol = bSym, message = bMsg)
                }
            }
        }

        // 3. Обновление текущего ИИ-портрета и настроения
        val refinedMood = json.optString("myRefinedMood", "")
        val serverPersona = json.optString("myCuratedPersona", "")
        val serverObs = json.optString("myCuratedObservation", "")

        _myCurrentTrack.value = current.copy(
            personaTitle = if (serverPersona.isNotBlank()) serverPersona else current.personaTitle,
            personaObservation = if (serverObs.isNotBlank()) serverObs else current.personaObservation,
            moodLabel = if (refinedMood.isNotBlank()) refinedMood else current.moodLabel,
            reactions = parsedMyReactions
        )

        // 4. Парсинг окружающих сигналов и Призрачных следов
        val sigArr = json.optJSONArray("nearbySignals") ?: json.optJSONArray("signals")
        if (sigArr != null) {
            val list = mutableListOf<EchoTrackSignal>()
            for (i in 0 until sigArr.length()) {
                val item = sigArr.getJSONObject(i)
                val sid = item.optString("id", "")
                if (sid == deviceId) continue

                val rObj = item.optJSONObject("reactions")
                val rMap = mutableMapOf<String, Int>()
                if (rObj != null) {
                    val rKeys = rObj.keys()
                    while (rKeys.hasNext()) {
                        val k = rKeys.next()
                        rMap[k] = rObj.optInt(k, 0)
                    }
                }

                list.add(
                    EchoTrackSignal(
                        id = sid,
                        artist = item.optString("artist", "Неизвестный артист"),
                        title = item.optString("title", "Без названия"),
                        sourceName = item.optString("sourceName", "ЭХО"),
                        distanceMeters = item.optInt("distanceMeters", 50),
                        angleDegrees = item.optDouble("angleDegrees", 45.0).toFloat(),
                        distanceRatio = item.optDouble("distanceRatio", 0.45).toFloat(),
                        personaTitle = item.optString("personaTitle", "Архивариус забытых кассет"),
                        personaObservation = item.optString("personaObservation", "Слушает музыку в вечерней тишине."),
                        moodLabel = item.optString("moodLabel", "Теплый винил"),
                        compatibilityPercent = item.optInt("compatibilityPercent", 85),
                        resonanceDays = item.optInt("resonanceDays", 1),
                        encountersCount = item.optInt("encountersCount", 1),
                        colorHex = item.optLong("colorHex", 0xFF38BDF8),
                        avatarPetals = item.optInt("avatarPetals", 6),
                        zoneName = item.optString("zoneName", "Вечерняя улица"),
                        reactions = rMap
                    )
                )
            }
            _nearbySignals.value = list
        }

        // 5. Парсинг тайников
        val capArr = json.optJSONArray("capsules")
        if (capArr != null) {
            val capList = mutableListOf<EchoTimeCapsule>()
            for (i in 0 until capArr.length()) {
                val c = capArr.getJSONObject(i)
                capList.add(
                    EchoTimeCapsule(
                        id = c.optString("id", ""),
                        creatorId = c.optString("creatorId", ""),
                        artist = c.optString("artist", ""),
                        title = c.optString("title", ""),
                        moodLabel = c.optString("moodLabel", "Теплый винил"),
                        personaTitle = c.optString("personaTitle", ""),
                        zoneName = c.optString("zoneName", "Вечерняя улица"),
                        secretMessage = c.optString("secretMessage", ""),
                        distanceMeters = c.optInt("distanceMeters", 60),
                        angleDegrees = c.optDouble("angleDegrees", 120.0).toFloat(),
                        distanceRatio = c.optDouble("distanceRatio", 0.5).toFloat(),
                        compatibilityPercent = c.optInt("compatibilityPercent", 90),
                        isUnlocked = c.optBoolean("isUnlocked", true)
                    )
                )
            }
            _nearbyCapsules.value = capList
        }

        // 6. Хроника вечерних следов
        val tracesArr = json.optJSONArray("eveningTraces")
        if (tracesArr != null) {
            val traces = mutableListOf<String>()
            for (i in 0 until tracesArr.length()) {
                traces.add(tracesArr.getString(i))
            }
            _eveningTraces.value = traces
        }

        // 7. Городские зоны
        val zonesArr = json.optJSONArray("cityZones")
        if (zonesArr != null) {
            val parsedZones = mutableListOf<CityZoneTrace>()
            for (i in 0 until zonesArr.length()) {
                val z = zonesArr.getJSONObject(i)
                parsedZones.add(
                    CityZoneTrace(
                        placeName = z.optString("placeName", "Вечерняя улица"),
                        dominantVibe = z.optString("dominantVibe", "Теплый синт"),
                        recentArtists = z.optString("recentArtists", ""),
                        listenersCount = z.optInt("listenersCount", 1)
                    )
                )
            }
            _liveCityZones.value = parsedZones
        }
    }

    private fun loadSavedTracksFromPrefs(p: SharedPreferences) {
        val raw = p.getString("saved_tracks_json", "") ?: ""
        if (raw.isBlank()) return
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SavedEchoTrack>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    SavedEchoTrack(
                        artist = o.optString("artist", ""),
                        title = o.optString("title", ""),
                        personaTitle = o.optString("personaTitle", ""),
                        moodLabel = o.optString("moodLabel", ""),
                        savedAt = o.optString("savedAt", "")
                    )
                )
            }
            _savedTracks.value = list
        } catch (_: Exception) {}
    }

    private fun saveSavedTracksToPrefs() {
        val p = prefs ?: return
        val arr = JSONArray()
        _savedTracks.value.forEach {
            arr.put(JSONObject().apply {
                put("artist", it.artist)
                put("title", it.title)
                put("personaTitle", it.personaTitle)
                put("moodLabel", it.moodLabel)
                put("savedAt", it.savedAt)
            })
        }
        p.edit().putString("saved_tracks_json", arr.toString()).apply()
    }
}
