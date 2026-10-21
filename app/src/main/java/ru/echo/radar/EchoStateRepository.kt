package ru.echo.radar

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs

data class EchoTrackSignal(
    val id: String,
    val artist: String,
    val title: String,
    val sourceName: String,
    val distanceMeters: Int,
    val angleDegrees: Float,
    val distanceRatio: Float,
    val personaTitle: String,
    val personaObservation: String,
    val moodLabel: String,
    val compatibilityPercent: Int,
    val resonanceDays: Int,
    val encountersCount: Int,
    val colorHex: Long,
    val avatarPetals: Int,
    val reactions: Map<String, Int>,
    val energyPercent: Int = 60,
    val duskPercent: Int = 75,
    val warmthPercent: Int = 65
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
    val vibeVector: Int,
    val energyPercent: Int,
    val duskPercent: Int,
    val warmthPercent: Int
)

private data class CuratorCluster(
    val id: String,
    val moodLabels: List<String>,
    val archetypes: List<String>,
    val epithets: List<String>,
    val realms: List<String>,
    val colors: List<Long>,
    val baseEnergy: Int,
    val baseDusk: Int,
    val baseWarmth: Int,
    val poeticNuance: String
)

object EchoStateRepository {

    private const val PREFS_NAME = "echo_radar_local_store"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SAVED_TRACKS = "saved_tracks_json"
    private const val KEY_CUSTOM_HOST = "saved_custom_host"

    private var myDeviceId = "echo_android_" + UUID.randomUUID().toString().take(6)
    private var appContext: Context? = null

    @Volatile
    private var customServerHost: String? = null

    private val _serverConnected = MutableStateFlow(false)
    val serverConnected: StateFlow<Boolean> = _serverConnected.asStateFlow()

    private val _activeServerHost = MutableStateFlow("10.0.2.2:8080")
    val activeServerHost: StateFlow<String> = _activeServerHost.asStateFlow()

    private val _presenceEnabled = MutableStateFlow(true)
    val presenceEnabled: StateFlow<Boolean> = _presenceEnabled.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _savedTracks = MutableStateFlow<List<SavedEchoTrack>>(emptyList())
    val savedTracks: StateFlow<List<SavedEchoTrack>> = _savedTracks.asStateFlow()

    private val _incomingBanner = MutableStateFlow<IncomingReactionBanner?>(null)
    val incomingBanner: StateFlow<IncomingReactionBanner?> = _incomingBanner.asStateFlow()

    val availableZones = listOf(
        "Вечерняя улица",
        "Парк Горького · Набережная",
        "Сретенка · Камерные бары",
        "Покровка · Ночные кофейни",
        "Северный лесопарк"
    )

    private val _myCurrentZone = MutableStateFlow(availableZones.first())
    val myCurrentZone: StateFlow<String> = _myCurrentZone.asStateFlow()

    private val _liveCityZones = MutableStateFlow(
        listOf(
            CityZoneTrace("Парк Горького · Набережная", "Ambient / Post-Rock", "Hammock, Sigur Rós, Brian Eno", 14),
            CityZoneTrace("Сретенка · Камерные бары", "Post-Punk / Darkwave", "Молчат Дома, Lebanon Hanover, The Cure", 9),
            CityZoneTrace("Покровка · Ночные кофейни", "Trip-Hop / Nu-Jazz", "Portishead, Bohren & der Club of Gore", 11),
            CityZoneTrace("Северный лесопарк", "Nordic Folk / Ambient", "Wardruna, Agalloch", 8)
        )
    )
    val liveCityZones: StateFlow<List<CityZoneTrace>> = _liveCityZones.asStateFlow()

    fun setMyZone(zone: String) {
        _myCurrentZone.value = zone
        thread(isDaemon = true) { syncWithLocalServer() }
    }

    fun setCustomServerIp(ipAddress: String) {
        val rawHost = ipAddress.removePrefix("ip:").removePrefix("IP:").removePrefix("http://").trim()
        if (rawHost.isEmpty()) {
            customServerHost = null
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.remove(KEY_CUSTOM_HOST)?.apply()
        } else {
            val formatted = if (rawHost.contains(":")) rawHost else "$rawHost:8080"
            customServerHost = formatted
            _activeServerHost.value = formatted
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_CUSTOM_HOST, formatted)?.apply()
        }
        thread(isDaemon = true) { syncWithLocalServer() }
    }

    private val _eveningTraces = MutableStateFlow(
        listOf(
            "✦ ИИ-куратор: Ожидание первой музыкальной волны...",
            "21:40 · Рядом прозвучал Coil — The Ape of Naples (~90 м)",
            "21:15 · Повторная встреча (3-й раз): слушатель Burial — Archangel"
        )
    )
    val eveningTraces: StateFlow<List<String>> = _eveningTraces.asStateFlow()

    private val sessionTrackHistory = mutableListOf<Pair<String, String>>()
    private var sameArtistStreak = 1
    private var lastBaseSourceName = "Системный перехват Android"

    // 6 смысловых эстетических кластеров ИИ-куратора
    private val curatorClusters = listOf(
        CuratorCluster(
            id = "folklore",
            moodLabels = listOf(
                "Ренессанс-фолк и лунная баллада",
                "Северный ритуал и древний лес",
                "Акустическая баллада и тёплый фолк",
                "Сумеречный фолк и сказания"
            ),
            archetypes = listOf("Хранитель", "Менестрель", "Летописец", "Путник", "Сказитель", "Часовой"),
            epithets = listOf("северных", "вересковых", "полуночных", "древних", "туманных", "лесных"),
            realms = listOf("баллад", "легенд", "троп", "костров", "ручьёв", "преданий"),
            colors = listOf(0xFF34D399L, 0xFF10B981L, 0xFFFBBF24L, 0xFF6EE7B7L),
            baseEnergy = 55,
            baseDusk = 70,
            baseWarmth = 88,
            poeticNuance = "Тёплая акустическая фактура пробуждает образы старинных маршрутов и живого огня."
        ),
        CuratorCluster(
            id = "postpunk",
            moodLabels = listOf(
                "Холодный пост-панк и неон",
                "Сумеречный нью-вейв",
                "Готический синт и тени кварталов"
            ),
            archetypes = listOf("Навигатор", "Сталкер", "Хроникёр", "Созерцатель", "Призрак", "Смотритель"),
            epithets = listOf("неоновых", "бетонных", "полуночных", "дождливых", "холодных", "пустынных"),
            realms = listOf("кварталов", "переулков", "крыш", "мостов", "фонарей", "высоток"),
            colors = listOf(0xFF38BDF8L, 0xFF818CF8L, 0xFF60A5FAL, 0xFF22D3EEL),
            baseEnergy = 68,
            baseDusk = 92,
            baseWarmth = 38,
            poeticNuance = "Отстранённая пульсация бас-гитары и эхо ночных проспектов."
        ),
        CuratorCluster(
            id = "electronic",
            moodLabels = listOf(
                "Ночной трип-хоп и винил",
                "Дождь и винил",
                "Гипнотический электронный пульс",
                "Городской даб и радиоволны"
            ),
            archetypes = listOf("Дешифровщик", "Ловец", "Алхимик", "Пилот", "Архивариус", "Оператор"),
            epithets = listOf("призрачных", "аналоговых", "глубоких", "винтажных", "скрытых", "мерцающих"),
            realms = listOf("радиоволн", "частот", "импульсов", "пластинок", "эхолотов", "лабиринтов"),
            colors = listOf(0xFFA78BFAL, 0xFFC084FCL, 0xFFE879F9L, 0xFF2DD4BFL),
            baseEnergy = 64,
            baseDusk = 86,
            baseWarmth = 62,
            poeticNuance = "Шорох виниловой дорожки переплетается с гипнотическим ритмом ночного мегаполиса."
        ),
        CuratorCluster(
            id = "ambient",
            moodLabels = listOf(
                "Глубокий эмбиент-эфир",
                "Кинематографичный эмбиент и созерцание",
                "Космическая невесомость"
            ),
            archetypes = listOf("Астроном", "Проводник", "Собиратель", "Свидетель", "Картограф", "Хранитель"),
            epithets = listOf("невесомых", "звёздных", "хрустальных", "тихих", "бесконечных", "зеркальных"),
            realms = listOf("горизонтов", "сновидений", "созвездий", "туманов", "орбит", "приливов"),
            colors = listOf(0xFF60A5FAL, 0xFF38BDF8L, 0xFF93C5FDL, 0xFFD8B4FEL),
            baseEnergy = 32,
            baseDusk = 80,
            baseWarmth = 74,
            poeticNuance = "Музыка растворяет границы времени, превращая маршрут в созерцательную паузу."
        ),
        CuratorCluster(
            id = "heavy",
            moodLabels = listOf(
                "Тяжёлый штормовой рифф",
                "Индустриальный пульс",
                "Электрический драйв и катарсис"
            ),
            archetypes = listOf("Кузнец", "Воин", "Дирижёр", "Охотник", "Пилот", "Хранитель"),
            epithets = listOf("штормовых", "грозовых", "электрических", "железных", "пламенных", "суровых"),
            realms = listOf("бастионов", "вихрей", "разрядов", "монолитов", "горизонтов", "симфоний"),
            colors = listOf(0xFFF97316L, 0xFFFB7185L, 0xFFEF4444L, 0xFFFBBF24L),
            baseEnergy = 95,
            baseDusk = 84,
            baseWarmth = 52,
            poeticNuance = "Плотная стена гитарного звука заряжает пространство вокруг высокой кинетической энергией."
        ),
        CuratorCluster(
            id = "melancholy_rock",
            moodLabels = listOf(
                "Тёплая гитарная меланхолия",
                "Гитарная меланхолия и ночной маршрут",
                "Гитарная невесомость и шугейз",
                "Бархатный ночной инди-эфир"
            ),
            archetypes = listOf("Архивариус", "Реставратор", "Поэт", "Странник", "Слушатель", "Коллекционер"),
            epithets = listOf("забытых", "тёплых", "осенних", "ламповых", "бархатных", "далёких"),
            realms = listOf("кассет", "маршрутов", "воспоминаний", "отголосков", "магнитных лент", "огней"),
            colors = listOf(0xFF38BDF8L, 0xFFF472B6L, 0xFF4ADE80L, 0xFFFDA4AFL),
            baseEnergy = 62,
            baseDusk = 72,
            baseWarmth = 82,
            poeticNuance = "Мелодичные гитарные переборы создают вокруг вас камерное, доверительное поле."
        )
    )

    private val _myCurrentTrack = MutableStateFlow(
        EchoTrackSignal(
            id = myDeviceId,
            artist = "Ожидание музыки...",
            title = "Включите трек в Яндекс Музыке / VK / Плеере",
            sourceName = "Системный перехват Android",
            distanceMeters = 0,
            angleDegrees = 0f,
            distanceRatio = 0f,
            personaTitle = "Архивариус ночных шорохов",
            personaObservation = "ИИ-куратор готов распознать вашу волну, вычислить 3 оси звучания и найти созвучных людей рядом.",
            moodLabel = "Ночное созерцание",
            compatibilityPercent = 100,
            resonanceDays = 0,
            encountersCount = 1,
            colorHex = 0xFF38BDF8L,
            avatarPetals = 7,
            reactions = emptyMap(),
            energyPercent = 52,
            duskPercent = 78,
            warmthPercent = 70
        )
    )
    val myCurrentTrack: StateFlow<EchoTrackSignal> = _myCurrentTrack.asStateFlow()

    private val _nearbySignals = MutableStateFlow(
        listOf(
            EchoTrackSignal(
                id = "bot_1",
                artist = "Radiohead",
                title = "Weird Fishes / Arpeggi",
                sourceName = "Яндекс Музыка",
                distanceMeters = 95,
                angleDegrees = 42f,
                distanceRatio = 0.38f,
                personaTitle = "Архивариус забытых кассет",
                personaObservation = "В последние недели выбирает длинные гитарные композиции с медленным развитием.",
                moodLabel = "Тёплая гитарная меланхолия",
                compatibilityPercent = 96,
                resonanceDays = 19,
                encountersCount = 3,
                colorHex = 0xFF38BDF8L,
                avatarPetals = 6,
                reactions = mapOf("🌊" to 4, "🌑" to 2, "🎼" to 3),
                energyPercent = 64,
                duskPercent = 74,
                warmthPercent = 85
            ),
            EchoTrackSignal(
                id = "bot_2",
                artist = "Wardruna",
                title = "Helvegen",
                sourceName = "VK Музыка",
                distanceMeters = 170,
                angleDegrees = 140f,
                distanceRatio = 0.64f,
                personaTitle = "Хранитель северных лесов",
                personaObservation = "Слушает нордический фолк, ренессанс-баллады и акустический эмбиент на вечерних маршрутах.",
                moodLabel = "Северный ритуал и древний лес",
                compatibilityPercent = 91,
                resonanceDays = 12,
                encountersCount = 2,
                colorHex = 0xFF34D399L,
                avatarPetals = 5,
                reactions = mapOf("🌲" to 6, "⚔" to 2, "🌑" to 1),
                energyPercent = 58,
                duskPercent = 82,
                warmthPercent = 88
            ),
            EchoTrackSignal(
                id = "bot_3",
                artist = "Burial",
                title = "Archangel",
                sourceName = "Poweramp FLAC",
                distanceMeters = 75,
                angleDegrees = 235f,
                distanceRatio = 0.30f,
                personaTitle = "Проводник ночных туманов",
                personaObservation = "Уходит от ломаных ритмов в сторону глубокого ночного гаража и даб-атмосферы.",
                moodLabel = "Дождь и винил",
                compatibilityPercent = 94,
                resonanceDays = 24,
                encountersCount = 5,
                colorHex = 0xFFA78BFAL,
                avatarPetals = 8,
                reactions = mapOf("🦇" to 3, "🌑" to 5, "☁" to 4),
                energyPercent = 60,
                duskPercent = 92,
                warmthPercent = 64
            )
        )
    )
    val nearbySignals: StateFlow<List<EchoTrackSignal>> = _nearbySignals.asStateFlow()

    init {
        // 1. Автопоиск сервера в локальной сети (UDP-бродкаст на порту 8888)
        thread(isDaemon = true, name = "EchoUdpDiscovery") {
            try {
                val socket = DatagramSocket(8888).apply {
                    broadcast = true
                }
                val buffer = ByteArray(256)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (message.startsWith("ECHO_SERVER_DISCOVERY:")) {
                        val serverPort = message.substringAfter(":").trim()
                        val discoveredIp = packet.address.hostAddress
                        if (!discoveredIp.isNullOrEmpty()) {
                            val targetHost = "$discoveredIp:$serverPort"
                            val savedManual = appContext
                                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                ?.getString(KEY_CUSTOM_HOST, null)
                            if (savedManual.isNullOrEmpty() && customServerHost != targetHost) {
                                customServerHost = targetHost
                                _activeServerHost.value = targetHost
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2. Фоновый поток синхронизации с сервером каждые 2.5 секунды
        thread(isDaemon = true, name = "EchoServerSync") {
            while (true) {
                try {
                    if (_presenceEnabled.value) {
                        syncWithLocalServer()
                    }
                } catch (_: Exception) {
                }
                Thread.sleep(2500)
            }
        }
    }

    fun initLocalStore(context: Context) {
        if (appContext != null) return
        val appCtx = context.applicationContext
        appContext = appCtx
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedId = prefs.getString(KEY_DEVICE_ID, null)
        if (savedId != null) {
            myDeviceId = savedId
            _myCurrentTrack.value = _myCurrentTrack.value.copy(id = myDeviceId)
        } else {
            prefs.edit().putString(KEY_DEVICE_ID, myDeviceId).apply()
        }

        val savedHost = prefs.getString(KEY_CUSTOM_HOST, null)
        if (!savedHost.isNullOrEmpty()) {
            customServerHost = savedHost
            _activeServerHost.value = savedHost
        }

        val rawJson = prefs.getString(KEY_SAVED_TRACKS, "[]") ?: "[]"
        try {
            val arr = JSONArray(rawJson)
            val loaded = mutableListOf<SavedEchoTrack>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                loaded.add(
                    SavedEchoTrack(
                        artist = o.optString("artist", ""),
                        title = o.optString("title", ""),
                        personaTitle = o.optString("personaTitle", ""),
                        moodLabel = o.optString("moodLabel", ""),
                        savedAt = o.optString("savedAt", "")
                    )
                )
            }
            _savedTracks.value = loaded
        } catch (_: Exception) {
        }
    }

    fun saveTrackToFinds(signal: EchoTrackSignal): Boolean {
        val currentList = _savedTracks.value.toMutableList()
        val alreadyExists = currentList.any {
            it.artist.equals(signal.artist, ignoreCase = true) &&
                it.title.equals(signal.title, ignoreCase = true)
        }
        if (alreadyExists) return false

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val item = SavedEchoTrack(
            artist = signal.artist,
            title = signal.title,
            personaTitle = signal.personaTitle,
            moodLabel = signal.moodLabel,
            savedAt = timeStr
        )
        currentList.add(0, item)
        _savedTracks.value = currentList
        persistSavedTracks(currentList)
        return true
    }

    fun removeSavedTrack(track: SavedEchoTrack) {
        val updated = _savedTracks.value.filterNot {
            it.artist == track.artist && it.title == track.title
        }
        _savedTracks.value = updated
        persistSavedTracks(updated)
    }

    private fun persistSavedTracks(list: List<SavedEchoTrack>) {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            list.forEach { t ->
                val obj = JSONObject().apply {
                    put("artist", t.artist)
                    put("title", t.title)
                    put("personaTitle", t.personaTitle)
                    put("moodLabel", t.moodLabel)
                    put("savedAt", t.savedAt)
                }
                arr.put(obj)
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SAVED_TRACKS, arr.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    fun dismissBanner() {
        _incomingBanner.value = null
    }

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun togglePresence() {
        _presenceEnabled.value = !_presenceEnabled.value
    }

    fun updateDetectedMusic(artist: String, title: String, sourcePackage: String) {
        updateMyPlayingTrack(artist, title, sourcePackage)
    }

    fun updateMyPlayingTrack(artist: String, title: String, sourcePackage: String) {
        val cleanArtist = artist.trim().take(90).ifEmpty { "Неизвестный исполнитель" }
        val cleanTitle = title.trim().take(90).ifEmpty { "Без названия" }

        // Защита от случайной вставки кода или IP в название трека
        if (cleanArtist.startsWith("package ") || cleanArtist.startsWith("import ") || cleanArtist.startsWith("http://")) {
            return
        }

        val current = _myCurrentTrack.value
        if (current.artist.equals(cleanArtist, ignoreCase = true) &&
            current.title.equals(cleanTitle, ignoreCase = true)
        ) {
            return
        }

        val previousArtist = if (sessionTrackHistory.isNotEmpty()) sessionTrackHistory.last().first else null
        val isSameArtist = previousArtist != null && previousArtist.equals(cleanArtist, ignoreCase = true)

        if (isSameArtist) {
            sameArtistStreak++
        } else {
            sameArtistStreak = 1
        }

        sessionTrackHistory.add(cleanArtist to cleanTitle)
        if (sessionTrackHistory.size > 30) {
            sessionTrackHistory.removeAt(0)
        }

        lastBaseSourceName = mapPackageToName(sourcePackage)
        val displaySource = if (_serverConnected.value) "$lastBaseSourceName · Эфир ПК ●" else lastBaseSourceName

        val curated = generateDynamicPersona(
            artist = cleanArtist,
            title = cleanTitle,
            previousArtist = previousArtist,
            isSameArtist = isSameArtist,
            streak = sameArtistStreak
        )

        _myCurrentTrack.value = current.copy(
            artist = cleanArtist,
            title = cleanTitle,
            sourceName = displaySource,
            personaTitle = curated.personaTitle,
            moodLabel = curated.moodLabel,
            personaObservation = curated.observation,
            colorHex = curated.colorHex,
            avatarPetals = curated.petals,
            energyPercent = curated.energyPercent,
            duskPercent = curated.duskPercent,
            warmthPercent = curated.warmthPercent
        )

        recalculateNearbyCompatibility(curated.vibeVector)
        thread(isDaemon = true) { syncWithLocalServer() }
    }

    fun sendSymbolReaction(signalId: String, symbol: String) {
        _nearbySignals.value = _nearbySignals.value.map { sig ->
            if (sig.id == signalId) {
                val updated = sig.reactions.toMutableMap()
                updated[symbol] = (updated[symbol] ?: 0) + 1
                sig.copy(reactions = updated)
            } else {
                sig
            }
        }
        thread(isDaemon = true) {
            postReactionToServer(signalId, symbol)
        }
    }

    private fun syncWithLocalServer() {
        val candidates = mutableListOf<String>()
        customServerHost?.let { if (it.isNotBlank()) candidates.add(it) }
        if (!candidates.contains("10.0.2.2:8080")) candidates.add("10.0.2.2:8080")
        if (!candidates.contains("127.0.0.1:8080")) candidates.add("127.0.0.1:8080")

        val myTrack = _myCurrentTrack.value
        val payload = JSONObject().apply {
            put("id", myDeviceId)
            put("artist", myTrack.artist)
            put("title", myTrack.title)
            put("sourceName", lastBaseSourceName)
            put("personaTitle", myTrack.personaTitle)
            put("personaObservation", myTrack.personaObservation)
            put("moodLabel", myTrack.moodLabel)
            put("colorHex", myTrack.colorHex)
            put("avatarPetals", myTrack.avatarPetals)
            put("zoneName", _myCurrentZone.value)
        }

        for (host in candidates) {
            try {
                val url = URL("http://$host/api/signal")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1500
                    readTimeout = 2000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                }

                if (conn.responseCode == 200) {
                    val responseText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                        it.readText()
                    }
                    _serverConnected.value = true
                    _activeServerHost.value = host
                    handleServerResponse(JSONObject(responseText))
                    return
                }
            } catch (_: Exception) {
            }
        }
        _serverConnected.value = false
    }

    private fun handleServerResponse(json: JSONObject) {
        val refinedMood = json.optString("myRefinedMood", "")
        val current = _myCurrentTrack.value
        val updatedSource = "$lastBaseSourceName · Эфир ПК ●"

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
                        message = "Слушатель рядом откликнулся на вашу волну «${current.artist}» символом $sym"
                    )
                }
            }
        }

        _myCurrentTrack.value = current.copy(
            moodLabel = if (refinedMood.isNotEmpty()) refinedMood else current.moodLabel,
            sourceName = updatedSource,
            reactions = parsedMyReactions
        )

        val arr = json.optJSONArray("nearbySignals")
        if (arr != null && arr.length() > 0) {
            val list = mutableListOf<EchoTrackSignal>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val artistStr = item.optString("artist", "Неизвестный")
                val titleStr = item.optString("title", "Без названия")
                val moodStr = item.optString("moodLabel", "Ночное созерцание")

                val reactObj = item.optJSONObject("reactions")
                val reactMap = mutableMapOf<String, Int>()
                if (reactObj != null) {
                    val keys = reactObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        reactMap[k] = reactObj.optInt(k, 0)
                    }
                }

                val cluster = matchCluster(artistStr.lowercase(), titleStr.lowercase(), moodStr.lowercase())
                val h = abs((artistStr + titleStr).hashCode())

                list.add(
                    EchoTrackSignal(
                        id = item.optString("id", "sig_$i"),
                        artist = artistStr,
                        title = titleStr,
                        sourceName = item.optString("sourceName", "Эфир"),
                        distanceMeters = item.optInt("distanceMeters", 120),
                        angleDegrees = item.optDouble("angleDegrees", 45.0).toFloat(),
                        distanceRatio = item.optDouble("distanceRatio", 0.5).toFloat(),
                        personaTitle = item.optString("personaTitle", "Странник эфира"),
                        personaObservation = item.optString("personaObservation", ""),
                        moodLabel = moodStr,
                        compatibilityPercent = item.optInt("compatibilityPercent", 92),
                        resonanceDays = item.optInt("resonanceDays", 10),
                        encountersCount = item.optInt("encountersCount", 2),
                        colorHex = item.optLong("colorHex", 0xFF38BDF8L),
                        avatarPetals = item.optInt("avatarPetals", 6),
                        reactions = reactMap,
                        energyPercent = (cluster.baseEnergy + (h % 19) - 9).coerceIn(25, 98),
                        duskPercent = (cluster.baseDusk + ((h / 3) % 17) - 8).coerceIn(30, 99),
                        warmthPercent = (cluster.baseWarmth + ((h / 7) % 19) - 9).coerceIn(25, 98)
                    )
                )
            }
            _nearbySignals.value = list
        }

        val tracesArr = json.optJSONArray("eveningTraces")
        if (tracesArr != null && tracesArr.length() > 0) {
            val traces = mutableListOf<String>()
            for (i in 0 until tracesArr.length()) {
                traces.add(tracesArr.getString(i))
            }
            _eveningTraces.value = traces
        }

        val zonesArr = json.optJSONArray("cityZones")
        if (zonesArr != null && zonesArr.length() > 0) {
            val parsedZones = mutableListOf<CityZoneTrace>()
            for (i in 0 until zonesArr.length()) {
                val z = zonesArr.getJSONObject(i)
                parsedZones.add(
                    CityZoneTrace(
                        placeName = z.optString("placeName", "Вечерняя улица"),
                        dominantVibe = z.optString("dominantVibe", "Ночное созерцание"),
                        recentArtists = z.optString("recentArtists", ""),
                        listenersCount = z.optInt("listenersCount", 1)
                    )
                )
            }
            _liveCityZones.value = parsedZones
        }
    }

    private fun postReactionToServer(targetId: String, symbol: String) {
        val candidates = mutableListOf<String>()
        customServerHost?.let { if (it.isNotBlank()) candidates.add(it) }
        if (!candidates.contains("10.0.2.2:8080")) candidates.add("10.0.2.2:8080")
        if (!candidates.contains("127.0.0.1:8080")) candidates.add("127.0.0.1:8080")

        val body = JSONObject().apply {
            put("targetId", targetId)
            put("symbol", symbol)
        }.toString()

        for (host in candidates) {
            try {
                val url = URL("http://$host/api/react")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1500
                    readTimeout = 1500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                if (conn.responseCode == 200) return
            } catch (_: Exception) {
            }
        }
    }

    private fun matchCluster(artistNorm: String, titleNorm: String, extraText: String = ""): CuratorCluster {
        val text = " $artistNorm $titleNorm $extraText "
        return when {
            listOf("blackmore", "renaissance", "faun", "мельница", "medieval", "celtic", "wardruna", "agalloch", "heilung", "фолк", "лес", "лун", "moon", "forest", "skald", "аркона").any { text.contains(it) } ->
                curatorClusters[0] // folklore
            listOf("кино", "молчат", "cure", "joy division", "дурной", "post-punk", "пост-панк", "неон", "lebanon", "перемотка", "буерак", "наутилус", "агата", "ночь").any { text.contains(it) } ->
                curatorClusters[1] // postpunk
            listOf("burial", "massive", "portishead", "tricky", "винил", "trip-hop", "трип-хоп", "bonobo", "prodigy", "электрон", "dub", "synth").any { text.contains(it) } ->
                curatorClusters[2] // electronic
            listOf("aphex", "ambient", "эмбиент", "eno", "coil", "drone", "hammock", "sigur", "dead can dance", "sleep", "dream", "космос").any { text.contains(it) } ->
                curatorClusters[3] // ambient
            listOf("metallica", "doom", "metal", "slayer", "megadeth", "rammstein", "ария", "кипелов", "sabbath", "opeth", "katatonia", "тяжёл", "шторм").any { text.contains(it) } ->
                curatorClusters[4] // heavy
            else -> {
                val idx = abs(artistNorm.hashCode()) % curatorClusters.size
                curatorClusters[idx]
            }
        }
    }

    private fun generateDynamicPersona(
        artist: String,
        title: String,
        previousArtist: String?,
        isSameArtist: Boolean,
        streak: Int
    ): CuratedPersona {
        val artistNorm = artist.lowercase().trim()
        val titleNorm = title.lowercase().trim()

        val cluster = matchCluster(artistNorm, titleNorm)
        val artistHash = abs(artistNorm.hashCode() * 31 + artistNorm.sumOf { it.code * 17 })
        val titleHash = abs(titleNorm.hashCode() * 37 + titleNorm.sumOf { it.code * 13 })
        val combinedHash = abs(artistHash xor titleHash)

        val arch = cluster.archetypes[artistHash % cluster.archetypes.size]
        val ep = cluster.epithets[(combinedHash / 3) % cluster.epithets.size]
        val realm = cluster.realms[(combinedHash / 7 + streak) % cluster.realms.size]

        val generatedTitle = "$arch $ep $realm"
        val mood = cluster.moodLabels[combinedHash % cluster.moodLabels.size]
        val selectedColor = cluster.colors[combinedHash % cluster.colors.size]
        val petals = 4 + (combinedHash % 9)

        val energy = (cluster.baseEnergy + (titleHash % 21) - 10).coerceIn(22, 99)
        val dusk = (cluster.baseDusk + (artistHash % 19) - 9).coerceIn(28, 99)
        val warmth = (cluster.baseWarmth + (combinedHash % 21) - 10).coerceIn(24, 99)

        val observation = when {
            previousArtist == null ->
                "✦ ${cluster.poeticNuance} ИИ-куратор зафиксировал ваш образ: «$generatedTitle»."
            isSameArtist && streak == 2 ->
                "✦ Вы углубляетесь в звучание «$artist» (2-й трек подряд). ${cluster.poeticNuance}"
            isSameArtist && streak >= 3 ->
                "✦ Устойчивый резонанс на волне «$artist» ($streak-я композиция). Ваша аура «$mood» сейчас максимально заметна на радаре."
            else ->
                "✦ Плавный переход от «$previousArtist» к «$artist»: вектор сместился в «$mood». ${cluster.poeticNuance}"
        }

        return CuratedPersona(
            personaTitle = generatedTitle,
            moodLabel = mood,
            observation = observation,
            colorHex = selectedColor,
            petals = petals,
            vibeVector = combinedHash % 100,
            energyPercent = energy,
            duskPercent = dusk,
            warmthPercent = warmth
        )
    }

    private fun recalculateNearbyCompatibility(myVibeVector: Int) {
        _nearbySignals.value = _nearbySignals.value.mapIndexed { idx, sig ->
            val targetVector = ((idx + 1) * 23) % 100
            val diff = abs(myVibeVector - targetVector)
            val newCompat = (98 - (diff / 3)).coerceIn(74, 99)
            sig.copy(compatibilityPercent = newCompat)
        }
    }

    private fun mapPackageToName(pkg: String): String {
        val p = pkg.lowercase()
        return when {
            p.contains("yandex") || p.contains("music.ya") -> "Яндекс Музыка"
            p.contains("vkontakte") || p.contains("vk.music") || p.contains("boom") -> "VK Музыка"
            p.contains("zvuk") || p.contains("sber") -> "Звук"
            p.contains("spotify") -> "Spotify"
            p.contains("apple") -> "Apple Music"
            p.contains("poweramp") -> "Poweramp"
            p.contains("aimp") -> "AIMP"
            p.contains("youtube") || p.contains("vanced") -> "YouTube Music"
            p.contains("chrome") || p.contains("browser") -> "Веб-плеер"
            p == "manual" -> "Ручной сигнал"
            p == "simulator" -> "Тест-волна ЭХО"
            else -> "Плеер ($pkg)"
        }
    }
}
