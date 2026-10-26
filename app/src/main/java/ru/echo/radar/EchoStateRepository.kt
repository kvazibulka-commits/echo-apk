package ru.echo.radar

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.ConcurrentHashMap

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
    val reactions: Map<String, Int> = emptyMap(),
    val isGhost: Boolean = false,
    val ageMinutes: Int = 0,
    val lat: Double = 55.7602,
    val lon: Double = 37.6185,
    val updatedAt: Long = 0L
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
    val isUnlocked: Boolean = true,
    val isVenue: Boolean = false,
    val venueName: String = "",
    val lat: Double = 55.7602,
    val lon: Double = 37.6185
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
    val listenersCount: Int,
    val fogColorHex: Long = 0xFF38BDF8,
    val fogRadius: Int = 500,
    val lat: Double = 55.7602,
    val lon: Double = 37.6185
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
    private val syncMutex = Mutex()
    private var prefs: SharedPreferences? = null

    @Volatile
    var deviceId: String = "echo_android_init"
        private set

    @Volatile
    var serverHost: String = "172.16.0.2:8080"
        private set

    @Volatile
    private var isCustomHostPinned: Boolean = false

    @Volatile
    private var isFirstSyncCompleted: Boolean = false

    @Volatile
    private var lastHandledBannerId: Long = 0L

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
    private val bumpedTargetsSession = ConcurrentHashMap.newKeySet<String>()

    private fun ensureOpaqueColor(rawColor: Long): Long {
        val rgb = rawColor and 0x00FFFFFFL
        return if (rgb == 0L) 0xFF38BDF8L else (rgb or 0xFF000000L)
    }

    private fun buildEndpointUrl(path: String): URL {
        val rawHost = serverHost.trim().removeSuffix("/")
        val cleanPath = if (path.startsWith("/")) path else "/$path"

        val baseUrl = when {
            rawHost.startsWith("https://", ignoreCase = true) ||
            rawHost.startsWith("http://", ignoreCase = true) -> rawHost
            rawHost.contains("trycloudflare.com", ignoreCase = true) ||
            rawHost.contains("ngrok", ignoreCase = true) ||
            rawHost.contains("loca.lt", ignoreCase = true) ||
            (!rawHost.contains(":") && rawHost.any { it.isLetter() }) -> "https://$rawHost"
            else -> "http://$rawHost"
        }
        return URL("$baseUrl$cleanPath")
    }

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
        _myCurrentTrack.value = _myCurrentTrack.value.copy(id = savedDevId)

        val savedIp = p.getString("server_ip", "") ?: ""
        if (savedIp.isNotBlank()) {
            serverHost = savedIp
            isCustomHostPinned = savedIp.contains("trycloudflare.com", ignoreCase = true)
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
        _myCurrentTrack.value = _myCurrentTrack.value.copy(zoneName = zone)
        prefs?.edit()?.putString("my_zone", zone)?.apply()
        scope.launch { sendSignalToServer() }
    }

    fun setCustomServerIp(ip: String) {
        val cleaned = ip.trim().removeSuffix("/")
        if (cleaned.isBlank()) return
        serverHost = cleaned
        isCustomHostPinned = cleaned.contains("trycloudflare.com", ignoreCase = true) ||
                             cleaned.startsWith("https://", ignoreCase = true)
        prefs?.edit()?.putString("server_ip", serverHost)?.apply()
        scope.launch { sendSignalToServer() }
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
        if (cur.artist.equals(cleanArtist, ignoreCase = true) &&
            cur.title.equals(cleanTitle, ignoreCase = true)
        ) {
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
            id = deviceId,
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

    fun bumpEncounter(targetId: String) {
        if (targetId.isBlank() || targetId == deviceId) return
        if (!bumpedTargetsSession.add(targetId)) return

        scope.launch {
            postJsonToServer("/api/bump_encounter", JSONObject().apply {
                put("targetId", targetId)
                put("senderId", deviceId)
            })
        }
    }

    fun sendSymbolReaction(targetId: String, symbol: String) {
        scope.launch {
            postJsonToServer("/api/react", JSONObject().apply {
                put("targetId", targetId)
                put("senderId", deviceId)
                put("symbol", symbol)
            })
        }
    }

    fun dropTimeCapsule(secretMessage: String) {
        val cur = _myCurrentTrack.value
        scope.launch {
            postJsonToServer("/api/drop_capsule", JSONObject().apply {
                put("creatorId", deviceId)
                put("artist", cur.artist)
                put("title", cur.title)
                put("moodLabel", cur.moodLabel)
                put("personaTitle", cur.personaTitle)
                put("zoneName", _myCurrentZone.value)
                put("secretMessage", secretMessage)
            })
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
        val shareIntent = Intent.createChooser(sendIntent, "Экспорт плейлиста вечера").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(shareIntent)
    }

    private fun startUdpDiscovery() {
        scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(8888))
                    soTimeout = 4000
                }
                val buffer = ByteArray(256)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        if (!isCustomHostPinned) {
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
                        }
                    } catch (_: Exception) {
                        delay(1500)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
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

    private suspend fun sendSignalToServer() {
        val cur = _myCurrentTrack.value
        val histArr = JSONArray()
        synchronized(localHistoryQueue) {
            localHistoryQueue.forEach { histArr.put(it) }
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
            put("colorHex", ensureOpaqueColor(cur.colorHex))
            put("avatarPetals", cur.avatarPetals)
            put("recentTracks", histArr)
        }
        postJsonToServer("/api/signal", body)
    }

    private suspend fun postJsonToServer(endpoint: String, body: JSONObject) {
        syncMutex.withLock {
            var conn: HttpURLConnection? = null
            try {
                val url = buildEndpointUrl(endpoint)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 4000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(body.toString())
                }
                if (conn.responseCode in 200..299) {
                    val respText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                        it.readText()
                    }
                    handleServerResponse(JSONObject(respText))
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun handleServerResponse(json: JSONObject) {
        val current = _myCurrentTrack.value

        val myReactObj = json.optJSONObject("myReactions")
        val parsedMyReactions = mutableMapOf<String, Int>()
        if (myReactObj != null) {
            val keys = myReactObj.keys()
            while (keys.hasNext()) {
                val sym = keys.next()
                val newCount = myReactObj.optInt(sym, 0)
                parsedMyReactions[sym] = newCount
                val oldCount = current.reactions[sym] ?: 0
                if (isFirstSyncCompleted && newCount > oldCount) {
                    val bId = System.currentTimeMillis()
                    lastHandledBannerId = bId
                    _incomingBanner.value = IncomingReactionBanner(
                        id = bId,
                        symbol = sym,
                        message = "Слушатель рядом откликнулся на волну «${current.artist}» символом $sym"
                    )
                }
            }
        }

        val bannerObj = json.optJSONObject("incomingBanner")
        if (bannerObj != null) {
            val bId = bannerObj.optLong("id", 0L)
            val targetId = bannerObj.optString("targetId", "")
            val senderId = bannerObj.optString("senderId", "")
            val bSym = bannerObj.optString("symbol", "✦")
            val bMsg = bannerObj.optString("message", "Вам отправлен импульс $bSym")

            val isNotFromMe = senderId != deviceId
            val isForMeOrBroadcast = targetId.isEmpty() || targetId == deviceId || targetId == "pc_studio_node"

            if (bId > 0L && bId != lastHandledBannerId && isNotFromMe && isForMeOrBroadcast) {
                lastHandledBannerId = bId
                _incomingBanner.value = IncomingReactionBanner(id = bId, symbol = bSym, message = bMsg)
            }
        }

        isFirstSyncCompleted = true

        val refinedMood = json.optString("myRefinedMood", "")
        val serverPersona = json.optString("myCuratedPersona", "")
        val serverObs = json.optString("myCuratedObservation", "")
        val serverColor = ensureOpaqueColor(json.optLong("myColorHex", current.colorHex))

        _myCurrentTrack.value = current.copy(
            id = deviceId,
            personaTitle = if (serverPersona.isNotBlank()) serverPersona else current.personaTitle,
            personaObservation = if (serverObs.isNotBlank()) serverObs else current.personaObservation,
            moodLabel = if (refinedMood.isNotBlank()) refinedMood else current.moodLabel,
            colorHex = serverColor,
            reactions = parsedMyReactions
        )

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

                val moodStr = item.optString("moodLabel", "Теплый винил")
                val obsStr = item.optString("personaObservation", "Слушает музыку в вечерней тишине.")
                val isGhostFlag = item.optBoolean("isGhost", moodStr.contains("Призрачный") || obsStr.contains("Призрачный"))

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
                        personaObservation = obsStr,
                        moodLabel = moodStr,
                        compatibilityPercent = item.optInt("compatibilityPercent", 85),
                        resonanceDays = item.optInt("resonanceDays", 1),
                        encountersCount = item.optInt("encountersCount", 1),
                        colorHex = ensureOpaqueColor(item.optLong("colorHex", 0xFF38BDF8)),
                        avatarPetals = item.optInt("avatarPetals", 6).coerceIn(3, 12),
                        zoneName = item.optString("zoneName", "Вечерняя улица"),
                        reactions = rMap,
                        isGhost = isGhostFlag,
                        ageMinutes = item.optInt("ageMinutes", 0),
                        lat = item.optDouble("lat", 55.7602),
                        lon = item.optDouble("lon", 37.6185),
                        updatedAt = (item.optDouble("updatedAt", 0.0) * 1000).toLong()
                    )
                )
            }
            _nearbySignals.value = list
        }

        val capArr = json.optJSONArray("capsules")
        if (capArr != null) {
            val capList = mutableListOf<EchoTimeCapsule>()
            for (i in 0 until capArr.length()) {
                val c = capArr.getJSONObject(i)
                val creator = c.optString("creatorId", "")
                val isVen = c.optBoolean("isVenue", creator.startsWith("venue_"))
                val venName = c.optString("venueName", "")

                capList.add(
                    EchoTimeCapsule(
                        id = c.optString("id", ""),
                        creatorId = creator,
                        artist = c.optString("artist", ""),
                        title = c.optString("title", ""),
                        moodLabel = c.optString("moodLabel", "Теплый винил"),
                        personaTitle = if (isVen && venName.isNotBlank()) venName else c.optString("personaTitle", ""),
                        zoneName = c.optString("zoneName", "Вечерняя улица"),
                        secretMessage = c.optString("secretMessage", ""),
                        distanceMeters = c.optInt("distanceMeters", 60),
                        angleDegrees = c.optDouble("angleDegrees", 120.0).toFloat(),
                        distanceRatio = c.optDouble("distanceRatio", 0.5).toFloat(),
                        compatibilityPercent = c.optInt("compatibilityPercent", 90),
                        isUnlocked = c.optBoolean("isUnlocked", true),
                        isVenue = isVen,
                        venueName = venName,
                        lat = c.optDouble("lat", 55.7602),
                        lon = c.optDouble("lon", 37.6185)
                    )
                )
            }
            _nearbyCapsules.value = capList
        }

        val tracesArr = json.optJSONArray("eveningTraces")
        if (tracesArr != null) {
            val traces = mutableListOf<String>()
            for (i in 0 until tracesArr.length()) {
                traces.add(tracesArr.getString(i))
            }
            _eveningTraces.value = traces
        }

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
                        listenersCount = z.optInt("listenersCount", 1),
                        fogColorHex = ensureOpaqueColor(z.optLong("fogColorHex", 0xFF38BDF8)),
                        fogRadius = z.optInt("fogRadius", 500),
                        lat = z.optDouble("lat", 55.7602),
                        lon = z.optDouble("lon", 37.6185)
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
