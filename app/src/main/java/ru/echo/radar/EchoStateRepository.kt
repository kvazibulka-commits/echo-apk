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
    val reactions: Map<String, Int>
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
    val distanceMeters: Int,
    val angleDegrees: Float,
    val distanceRatio: Float,
    val compatibilityPercent: Int,
    val isUnlocked: Boolean
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
    val vibeVector: Int
)

private data class ClusterLexicon(
    val archetypes: List<String>,
    val epithets: List<String>,
    val realms: List<String>
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

    @Volatile
    private var serverOnline: Boolean = false

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
    val cityZones: List<CityZoneTrace> get() = _liveCityZones.value

    private val _nearbyCapsules = MutableStateFlow(
        listOf(
            EchoTimeCapsule(
                id = "cap_1",
                creatorId = "bot_2",
                artist = "Dead Can Dance",
                title = "Host of Seraphim",
                moodLabel = "Сумеречный неофолк и легенды",
                personaTitle = "Летописец курганных преданий",
                zoneName = "Парк Горького · Набережная",
                secretMessage = "Слушаю этот трек у воды, пока зажигаются огни на мосту. Если наш резонанс совпал — значит, ты тоже слышишь тишину между нотами.",
                distanceMeters = 60,
                angleDegrees = 88f,
                distanceRatio = 0.46f,
                compatibilityPercent = 89,
                isUnlocked = true
            ),
            EchoTimeCapsule(
                id = "cap_2",
                creatorId = "bot_1",
                artist = "Slowdive",
                title = "Star Roving",
                moodLabel = "Шугейз и туманный дрим-поп",
                personaTitle = "Киномеханик дождливых крыш",
                zoneName = "Покровка · Ночные кофейни",
                secretMessage = "Тайник для ценителей гитарного тумана. Секретный пароль на фильтр-кофе в ночной кофейне у арки: ЭХО-ПОКРОВКА.",
                distanceMeters = 115,
                angleDegrees = 305f,
                distanceRatio = 0.58f,
                compatibilityPercent = 82,
                isUnlocked = false
            )
        )
    )
    val nearbyCapsules: StateFlow<List<EchoTimeCapsule>> = _nearbyCapsules.asStateFlow()

    fun setMyZone(zone: String) {
        _myCurrentZone.value = zone
        thread(isDaemon = true) { syncWithLocalServer() }
    }

    fun setCustomServerIp(ipAddress: String) {
        val rawHost = ipAddress.removePrefix("ip:").removePrefix("IP:").trim()
        customServerHost = if (rawHost.contains(":")) rawHost else "$rawHost:8080"

        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_CUSTOM_HOST, customServerHost)?.apply()

        thread(isDaemon = true) { syncWithLocalServer() }
    }

    fun dropTimeCapsule(secretMessage: String) {
        val cleanMsg = secretMessage.trim().take(140)
        if (cleanMsg.isEmpty()) return

        val myTrack = _myCurrentTrack.value
        val zone = _myCurrentZone.value
        val newCap = EchoTimeCapsule(
            id = "cap_local_${System.currentTimeMillis() % 100000}",
            creatorId = myDeviceId,
            artist = myTrack.artist,
            title = myTrack.title,
            moodLabel = myTrack.moodLabel,
            personaTitle = myTrack.personaTitle,
            zoneName = zone,
            secretMessage = cleanMsg,
            distanceMeters = 15,
            angleDegrees = ((_nearbyCapsules.value.size + 1) * 115f) % 360f,
            distanceRatio = 0.28f,
            compatibilityPercent = 100,
            isUnlocked = true
        )
        _nearbyCapsules.value = listOf(newCap) + _nearbyCapsules.value

        thread(isDaemon = true) {
            postCapsuleToServer(newCap)
            syncWithLocalServer()
        }
    }

    private val _eveningTraces = MutableStateFlow(
        listOf(
            "21:40 · Рядом прозвучал Coil — The Ape of Naples (~90 м)",
            "21:15 · Повторная встреча (3-й раз): слушатель Burial — Archangel",
            "20:50 · Музыкальный резонанс в парке: Dead Can Dance — Host of Seraphim"
        )
    )
    val eveningTraces: StateFlow<List<String>> = _eveningTraces.asStateFlow()
    val eveningHistory: List<String> get() = _eveningTraces.value

    private val sessionTrackHistory = mutableListOf<Pair<String, String>>()
    private val recentUsedWords = ArrayDeque<String>()
    private var sameArtistStreak = 1
    private var lastBaseSourceName = "Системный перехват Android"

    private val avatarColorPalette = listOf(
        0xFF38BDF8L, 0xFFA78BFAL, 0xFF34D399L, 0xFFF472B6L, 0xFFFBBF24L,
        0xFF60A5FAL, 0xFFFB7185L, 0xFF2DD4BFL, 0xFF818CF8L, 0xFFC084FCL,
        0xFF4ADE80L, 0xFFF97316L, 0xFFE879F9L, 0xFF22D3EEL, 0xFFA3E635L,
        0xFFEF4444L, 0xFF93C5FDL, 0xFFD8B4FEL, 0xFF6EE7B7L, 0xFFFDA4AFL
    )

    private val clusterLexicons = mapOf(
        "folklore" to ClusterLexicon(
            archetypes = listOf("Менестрель", "Хранитель", "Летописец", "Следопыт", "Звездочёт", "Скальд", "Пилигрим", "Вестник", "Травник", "Бард"),
            epithets = listOf("вересковых", "рунических", "заповедных", "курганных", "полуденных", "костровых", "лунных", "древних", "лесных", "озёрных"),
            realms = listOf("баллад", "преданий", "чащоб", "фьордов", "капищ", "троп", "холмов", "святилищ", "былин", "истоков")
        ),
        "melancholy_rock" to ClusterLexicon(
            archetypes = listOf("Архивариус", "Хроникёр", "Созерцатель", "Киномеханик", "Наблюдатель", "Реставратор", "Поэт", "Переводчик", "Коллекционер", "Свидетель"),
            epithets = listOf("панельных", "кассетных", "дождливых", "чердачных", "трамвайных", "осенних", "полуночных", "фонарных", "оконных", "бессонных"),
            realms = listOf("кварталов", "переулков", "мостов", "крыш", "набережных", "перронов", "проспектов", "витрин", "маршрутов", "карнизов")
        ),
        "night_electronic" to ClusterLexicon(
            archetypes = listOf("Дешифровщик", "Навигатор", "Алхимик", "Картограф", "Ловец", "Астроном", "Инженер", "Часовщик", "Радист", "Куратор"),
            epithets = listOf("неоновых", "аналоговых", "призрачных", "голографических", "ламповых", "магнитных", "зеркальных", "орбитальных", "спектральных", "невесомых"),
            realms = listOf("частот", "радиоволн", "сновидений", "пластинок", "миражей", "созвездий", "лабиринтов", "эхолотов", "сигналов", "орбит")
        ),
        "heavy" to ClusterLexicon(
            archetypes = listOf("Командор", "Кузнец", "Буревестник", "Фортификатор", "Лоцман", "Дозорный", "Механик", "Штурман", "Бастион", "Капитан"),
            epithets = listOf("штормовых", "грозовых", "стальных", "базальтовых", "вулканических", "громовых", "индустриальных", "раскалённых", "железных", "гранитных"),
            realms = listOf("бастионов", "реакторов", "рифов", "кратеров", "ущелий", "магистралей", "турбин", "монолитов", "разломов", "крепостей")
        ),
        "general" to ClusterLexicon(
            archetypes = listOf("Проводник", "Слушатель", "Искатель", "Пилот", "Дирижёр", "Смотритель", "Исследователь", "Собиратель", "Странник", "Охотник"),
            epithets = listOf("вечерних", "тихих", "скрытых", "мерцающих", "далёких", "бархатных", "хрустальных", "заснеженных", "таинственных", "чистых"),
            realms = listOf("отголосков", "симфоний", "станций", "воспоминаний", "резонансов", "импульсов", "огней", "океанов", "теней", "сюжетов")
        )
    )

    private val moodLabels = listOf(
        "Ночное созерцание", "Холодный пост-панк и неон", "Тёплая гитарная меланхолия",
        "Северный ритуал и древний лес", "Ночной трип-хоп и винил", "Тяжёлый штормовой рифф",
        "Кинематографичный эмбиент и созерцание", "Шугейз и туманный дрим-поп", "Пост-рок и широкие горизонты",
        "Бархатный ночной дарк-джаз", "Сумеречный неофолк и легенды", "Ночной синтвейв и ретро-неон",
        "Акустическая баллада и тёплый фолк", "Ренессанс-фолк и лунная баллада", "Гипнотический электронный пульс"
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
            personaTitle = "Проводник вечерних отголосков",
            personaObservation = "Ваш литературный образ и геометрия аватара перестраиваются под каждую композицию.",
            moodLabel = "Ночное созерцание",
            compatibilityPercent = 100,
            resonanceDays = 0,
            encountersCount = 1,
            colorHex = 0xFF38BDF8L,
            avatarPetals = 7,
            reactions = emptyMap()
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
                distanceMeters = 110,
                angleDegrees = 38f,
                distanceRatio = 0.42f,
                personaTitle = "Архивариус кассетных переулков",
                personaObservation = "В последние недели всё чаще выбирает длинные гитарные композиции с медленным развитием.",
                moodLabel = "Тёплая гитарная меланхолия",
                compatibilityPercent = 96,
                resonanceDays = 19,
                encountersCount = 3,
                colorHex = 0xFF38BDF8L,
                avatarPetals = 6,
                reactions = mapOf("🌊" to 4, "🌑" to 2, "🎼" to 3)
            ),
            EchoTrackSignal(
                id = "bot_2",
                artist = "Wardruna",
                title = "Helvegen",
                sourceName = "VK Музыка",
                distanceMeters = 185,
                angleDegrees = 135f,
                distanceRatio = 0.68f,
                personaTitle = "Скальд рунических фьордов",
                personaObservation = "Слушает нордический фолк и ритуальный эмбиент во время вечерних маршрутов.",
                moodLabel = "Северный ритуал и древний лес",
                compatibilityPercent = 91,
                resonanceDays = 12,
                encountersCount = 2,
                colorHex = 0xFF34D399L,
                avatarPetals = 5,
                reactions = mapOf("🌲" to 6, "⚔" to 2, "🌑" to 1)
            ),
            EchoTrackSignal(
                id = "bot_3",
                artist = "Burial",
                title = "Archangel",
                sourceName = "Poweramp FLAC",
                distanceMeters = 85,
                angleDegrees = 225f,
                distanceRatio = 0.33f,
                personaTitle = "Дешифровщик ламповых радиоволн",
                personaObservation = "Постепенно уходит от ломаных ритмов в сторону глубокого ночного дабстепа и гаража.",
                moodLabel = "Дождь и винил",
                compatibilityPercent = 94,
                resonanceDays = 24,
                encountersCount = 5,
                colorHex = 0xFFA78BFAL,
                avatarPetals = 8,
                reactions = mapOf("🦇" to 3, "🌑" to 5, "☁" to 4)
            )
        )
    )
    val nearbySignals: StateFlow<List<EchoTrackSignal>> = _nearbySignals.asStateFlow()

    init {
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
                            if (customServerHost != targetHost && appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getString(KEY_CUSTOM_HOST, null).isNullOrEmpty()) {
                                customServerHost = targetHost
                                serverOnline = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

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
        val cleanArtist = artist.trim().ifEmpty { "Неизвестный исполнитель" }
        val cleanTitle = title.trim().ifEmpty { "Без названия" }

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
        val displaySource = if (serverOnline) "$lastBaseSourceName · Эфир ПК ●" else lastBaseSourceName

        val curated = generateDynamicPersona(
            artist = cleanArtist,
            title = cleanTitle,
            previousArtist = previousArtist,
            isSameArtist = isSameArtist,
            streak = sameArtistStreak,
            totalTracksInSession = sessionTrackHistory.size
        )

        _myCurrentTrack.value = current.copy(
            artist = cleanArtist,
            title = cleanTitle,
            sourceName = displaySource,
            personaTitle = curated.personaTitle,
            moodLabel = curated.moodLabel,
            personaObservation = curated.observation,
            colorHex = curated.colorHex,
            avatarPetals = curated.petals
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
        customServerHost?.let { candidates.add(it) }
        candidates.add("10.0.2.2:8080")
        candidates.add("127.0.0.1:8080")

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
                    handleServerResponse(JSONObject(responseText))
                    serverOnline = true
                    return
                }
            } catch (_: Exception) {
            }
        }
        serverOnline = false
    }

    private fun handleServerResponse(json: JSONObject) {
        val refinedMood = json.optString("myRefinedMood", "")
        val serverPersona = json.optString("myCuratedPersona", "")
        val serverObs = json.optString("myCuratedObservation", "")
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
            personaTitle = if (serverPersona.isNotEmpty()) serverPersona else current.personaTitle,
            personaObservation = if (serverObs.isNotEmpty()) serverObs else current.personaObservation,
            moodLabel = if (refinedMood.isNotEmpty()) refinedMood else current.moodLabel,
            sourceName = updatedSource,
            reactions = parsedMyReactions
        )

        val arr = json.optJSONArray("nearbySignals")
        if (arr != null && arr.length() > 0) {
            val list = mutableListOf<EchoTrackSignal>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val reactObj = item.optJSONObject("reactions")
                val reactMap = mutableMapOf<String, Int>()
                if (reactObj != null) {
                    val keys = reactObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        reactMap[k] = reactObj.optInt(k, 0)
                    }
                }
                list.add(
                    EchoTrackSignal(
                        id = item.optString("id", "sig_$i"),
                        artist = item.optString("artist", "Неизвестный"),
                        title = item.optString("title", "Без названия"),
                        sourceName = item.optString("sourceName", "Эфир"),
                        distanceMeters = item.optInt("distanceMeters", 120),
                        angleDegrees = item.optDouble("angleDegrees", 45.0).toFloat(),
                        distanceRatio = item.optDouble("distanceRatio", 0.5).toFloat(),
                        personaTitle = item.optString("personaTitle", "Странник эфира"),
                        personaObservation = item.optString("personaObservation", ""),
                        moodLabel = item.optString("moodLabel", "Ночное созерцание"),
                        compatibilityPercent = item.optInt("compatibilityPercent", 92),
                        resonanceDays = item.optInt("resonanceDays", 10),
                        encountersCount = item.optInt("encountersCount", 2),
                        colorHex = item.optLong("colorHex", 0xFF38BDF8L),
                        avatarPetals = item.optInt("avatarPetals", 6),
                        reactions = reactMap
                    )
                )
            }
            _nearbySignals.value = list
        }

        val capsArr = json.optJSONArray("capsules")
        if (capsArr != null && capsArr.length() > 0) {
            val parsedCaps = mutableListOf<EchoTimeCapsule>()
            for (i in 0 until capsArr.length()) {
                val c = capsArr.getJSONObject(i)
                parsedCaps.add(
                    EchoTimeCapsule(
                        id = c.optString("id", "cap_$i"),
                        creatorId = c.optString("creatorId", ""),
                        artist = c.optString("artist", "Неизвестный"),
                        title = c.optString("title", "Без названия"),
                        moodLabel = c.optString("moodLabel", "Ночное созерцание"),
                        personaTitle = c.optString("personaTitle", "Странник эфира"),
                        zoneName = c.optString("zoneName", "Вечерняя улица"),
                        secretMessage = c.optString("secretMessage", ""),
                        distanceMeters = c.optInt("distanceMeters", 60),
                        angleDegrees = c.optDouble("angleDegrees", 90.0).toFloat(),
                        distanceRatio = c.optDouble("distanceRatio", 0.45).toFloat(),
                        compatibilityPercent = c.optInt("compatibilityPercent", 85),
                        isUnlocked = c.optBoolean("isUnlocked", false)
                    )
                )
            }
            _nearbyCapsules.value = parsedCaps
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

    private fun postCapsuleToServer(capsule: EchoTimeCapsule) {
        val candidates = mutableListOf<String>()
        customServerHost?.let { candidates.add(it) }
        candidates.add("10.0.2.2:8080")
        candidates.add("127.0.0.1:8080")

        val body = JSONObject().apply {
            put("creatorId", capsule.creatorId)
            put("artist", capsule.artist)
            put("title", capsule.title)
            put("moodLabel", capsule.moodLabel)
            put("personaTitle", capsule.personaTitle)
            put("zoneName", capsule.zoneName)
            put("secretMessage", capsule.secretMessage)
        }.toString()

        for (host in candidates) {
            try {
                val url = URL("http://$host/api/drop_capsule")
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

    private fun postReactionToServer(targetId: String, symbol: String) {
        val candidates = mutableListOf<String>()
        customServerHost?.let { candidates.add(it) }
        candidates.add("10.0.2.2:8080")
        candidates.add("127.0.0.1:8080")

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

    private fun resolveClusterKey(mood: String): String {
        val m = mood.lowercase()
        return when {
            m.contains("фолк") || m.contains("ритуал") || m.contains("баллада") || m.contains("средневеков") -> "folklore"
            m.contains("пост-панк") || m.contains("меланхолия") || m.contains("шугейз") || m.contains("пост-рок") -> "melancholy_rock"
            m.contains("трип-хоп") || m.contains("эмбиент") || m.contains("винил") || m.contains("синтвейв") || m.contains("джаз") || m.contains("электрон") -> "night_electronic"
            m.contains("рифф") || m.contains("дум") || m.contains("индустриал") || m.contains("драйв") -> "heavy"
            else -> "general"
        }
    }

    private fun generateDynamicPersona(
        artist: String,
        title: String,
        previousArtist: String?,
        isSameArtist: Boolean,
        streak: Int,
        totalTracksInSession: Int
    ): CuratedPersona {
        val artistNorm = artist.lowercase().trim()
        val titleNorm = title.lowercase().trim()
        val fullKey = "$artistNorm::$titleNorm"

        val artistHash = abs(artistNorm.hashCode() * 31 + artistNorm.sumOf { it.code * 17 })
        val titleHash = abs(titleNorm.hashCode() * 37 + titleNorm.sumOf { it.code * 13 })
        val combinedHash = abs(fullKey.hashCode() xor (artistHash + titleHash))

        val mood = detectMoodOrFallback(artistNorm, titleNorm, combinedHash)
        val clusterKey = resolveClusterKey(mood)
        val lexicon = clusterLexicons[clusterKey] ?: clusterLexicons.getValue("general")

        val forbiddenWords = mutableSetOf<String>()
        forbiddenWords.addAll(recentUsedWords)
        _nearbySignals.value.forEach { sig ->
            sig.personaTitle.split(" ").forEach { w -> forbiddenWords.add(w.lowercase()) }
        }

        val archs = lexicon.archetypes
        val epis = lexicon.epithets
        val realms = lexicon.realms

        var chosenArch = archs[artistHash % archs.size]
        for (i in archs.indices) {
            val cand = archs[(artistHash + i) % archs.size]
            if (cand.lowercase() !in forbiddenWords) {
                chosenArch = cand
                break
            }
        }

        var chosenEpi = epis[(combinedHash / 5) % epis.size]
        for (i in epis.indices) {
            val cand = epis[(combinedHash / 5 + i) % epis.size]
            if (cand.lowercase() !in forbiddenWords) {
                chosenEpi = cand
                break
            }
        }

        var chosenRealm = realms[(titleHash / 7) % realms.size]
        for (i in realms.indices) {
            val cand = realms[(titleHash / 7 + i) % realms.size]
            if (cand.lowercase() !in forbiddenWords) {
                chosenRealm = cand
                break
            }
        }

        listOf(chosenArch.lowercase(), chosenEpi.lowercase(), chosenRealm.lowercase()).forEach { w ->
            recentUsedWords.addLast(w)
            if (recentUsedWords.size > 24) recentUsedWords.removeFirst()
        }

        val generatedTitle = "$chosenArch $chosenEpi $chosenRealm"
        val colorIdx = (combinedHash + totalTracksInSession * 3) % avatarColorPalette.size
        val selectedColor = avatarColorPalette[colorIdx]
        val petals = 4 + (combinedHash % 9)

        val observation = when {
            previousArtist == null ->
                "ИИ-куратор настроил ваш первичный эфир на волну «$artist». Образ: $generatedTitle ($mood)."
            isSameArtist && streak == 2 ->
                "Вы остаётесь на волне «$artist» (2-й трек подряд). Образ «$generatedTitle» раскрывает новые грани кластера."
            isSameArtist && streak >= 3 ->
                "Глубокое погружение в «$artist» ($streak-я композиция подряд). Ваша музыкальная фаза стабильна («$mood»)."
            else ->
                "Смена волны с «$previousArtist» на «$artist»: звучание развернулось в сторону «$mood». Сформирован уникальный образ."
        }

        return CuratedPersona(
            personaTitle = generatedTitle,
            moodLabel = mood,
            observation = observation,
            colorHex = selectedColor,
            petals = petals,
            vibeVector = combinedHash % 100
        )
    }

    private fun detectMoodOrFallback(artistNorm: String, titleNorm: String, hash: Int): String {
        val text = " $artistNorm $titleNorm "
        return when {
            text.contains("blackmore") || text.contains("renaissance") || text.contains("faun") ||
                text.contains("мельница") || text.contains("medieval") || text.contains("celtic") ->
                "Ренессанс-фолк и лунная баллада"
            text.contains("wardruna") || text.contains("agalloch") || text.contains("heilung") ||
                text.contains("фолк") || text.contains("eolya") || text.contains("barsm") ->
                "Северный ритуал и древний лес"
            text.contains("slowdive") || text.contains("cocteau") || text.contains("mazzy") ||
                text.contains("cigarettes after") || text.contains("shoegaze") ->
                "Шугейз и туманный дрим-поп"
            text.contains("кино") || text.contains("молчат") || text.contains("cure") ||
                text.contains("дурной") || text.contains("post-punk") ->
                "Холодный пост-панк и неон"
            text.contains("burial") || text.contains("massive") || text.contains("portishead") ||
                text.contains("tricky") ->
                "Ночной трип-хоп и винил"
            text.contains("aphex") || text.contains("ambient") || text.contains("eno") ||
                text.contains("coil") || text.contains("drone") ->
                "Глубокий эмбиент-эфир"
            text.contains("metallica") || text.contains("doom") || text.contains("black metal") ||
                text.contains("slayer") || text.contains("megadeth") ->
                "Тяжёлый штормовой рифф"
            text.contains("radiohead") || text.contains("muse") || text.contains("placebo") ||
                text.contains("interpol") ->
                "Тёплая гитарная меланхолия"
            else -> moodLabels[hash % moodLabels.size]
        }
    }

    private fun recalculateNearbyCompatibility(myVibeVector: Int) {
        _nearbySignals.value = _nearbySignals.value.mapIndexed { idx, sig ->
            val targetVector = ((idx + 1) * 23) % 100
            val diff = abs(myVibeVector - targetVector)
            val newCompat = (98 - (diff / 3)).coerceIn(72, 99)
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
            p == "simulator" -> "Тест-сигнал ЭХО"
            else -> "Системный плеер ($pkg)"
        }
    }
}
