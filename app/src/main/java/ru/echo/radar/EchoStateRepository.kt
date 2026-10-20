package ru.echo.radar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

data class CityZoneTrace(
    val placeName: String,
    val dominantVibe: String,
    val recentArtists: String,
    val listenersCount: Int
)

object EchoStateRepository {

    private val _presenceEnabled = MutableStateFlow(true)
    val presenceEnabled: StateFlow<Boolean> = _presenceEnabled.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _myCurrentTrack = MutableStateFlow(
        EchoTrackSignal(
            id = "self",
            artist = "Ожидание музыки...",
            title = "Включите трек в Яндекс Музыке / VK / Плеере",
            sourceName = "Системный перехват Android",
            distanceMeters = 0,
            angleDegrees = 0f,
            distanceRatio = 0f,
            personaTitle = "Архивариус ночных шорохов",
            personaObservation = "Ваш профиль обновляется автоматически при каждом новом треке в наушниках.",
            moodLabel = "Ночное созерцание",
            compatibilityPercent = 100,
            resonanceDays = 0,
            encountersCount = 1,
            colorHex = 0xFF38BDF8,
            avatarPetals = 7,
            reactions = emptyMap()
        )
    )
    val myCurrentTrack: StateFlow<EchoTrackSignal> = _myCurrentTrack.asStateFlow()

    private val _nearbySignals = MutableStateFlow(
        listOf(
            EchoTrackSignal(
                id = "sig_1",
                artist = "Radiohead",
                title = "Weird Fishes / Arpeggi",
                sourceName = "Яндекс Музыка",
                distanceMeters = 110,
                angleDegrees = 38f,
                distanceRatio = 0.42f,
                personaTitle = "Архивариус забытых кассет",
                personaObservation = "В последние недели всё чаще выбирает длинные гитарные композиции с медленным развитием.",
                moodLabel = "Теплая меланхолия",
                compatibilityPercent = 96,
                resonanceDays = 19,
                encountersCount = 3,
                colorHex = 0xFF38BDF8,
                avatarPetals = 6,
                reactions = mapOf("🌊" to 4, "🌑" to 2, "🎼" to 3)
            ),
            EchoTrackSignal(
                id = "sig_2",
                artist = "Wardruna",
                title = "Helvegen",
                sourceName = "VK Музыка",
                distanceMeters = 185,
                angleDegrees = 135f,
                distanceRatio = 0.68f,
                personaTitle = "Хранитель северных лесов",
                personaObservation = "Слушает нордический фолк и ритуальный эмбиент во время вечерних маршрутов.",
                moodLabel = "Северный ритуал",
                compatibilityPercent = 91,
                resonanceDays = 12,
                encountersCount = 2,
                colorHex = 0xFF34D399,
                avatarPetals = 5,
                reactions = mapOf("🌲" to 6, "⚔" to 2, "🌑" to 1)
            ),
            EchoTrackSignal(
                id = "sig_3",
                artist = "Burial",
                title = "Archangel",
                sourceName = "Poweramp FLAC",
                distanceMeters = 85,
                angleDegrees = 225f,
                distanceRatio = 0.33f,
                personaTitle = "Проводник туманов",
                personaObservation = "Постепенно уходит от ломаных ритмов в сторону глубокого ночного дабстепа и гаража.",
                moodLabel = "Дождь и винил",
                compatibilityPercent = 94,
                resonanceDays = 24,
                encountersCount = 5,
                colorHex = 0xFFA78BFA,
                avatarPetals = 8,
                reactions = mapOf("🦇" to 3, "🌑" to 5, "☁" to 4)
            ),
            EchoTrackSignal(
                id = "sig_4",
                artist = "Massive Attack",
                title = "Angel",
                sourceName = "Звук",
                distanceMeters = 240,
                angleDegrees = 310f,
                distanceRatio = 0.82f,
                personaTitle = "Меланхоличный космонавт",
                personaObservation = "Предпочитает густой басовый трип-хоп и мрачную бристольскую электронику.",
                moodLabel = "Ночной импульс",
                compatibilityPercent = 89,
                resonanceDays = 7,
                encountersCount = 1,
                colorHex = 0xFFF472B6,
                avatarPetals = 7,
                reactions = mapOf("🦇" to 5, "🌑" to 3)
            ),
            EchoTrackSignal(
                id = "sig_5",
                artist = "Agalloch",
                title = "Falling Snow",
                sourceName = "Локальный плеер",
                distanceMeters = 150,
                angleDegrees = 82f,
                distanceRatio = 0.56f,
                personaTitle = "Путник зимних сумерек",
                personaObservation = "Сочетает атмосферный пост-метал с акустическими неофолк-интерлюдиями.",
                moodLabel = "Холодный рассвет",
                compatibilityPercent = 93,
                resonanceDays = 15,
                encountersCount = 4,
                colorHex = 0xFF60A5FA,
                avatarPetals = 9,
                reactions = mapOf("🌲" to 4, "⚔" to 3, "☁" to 2)
            )
        )
    )
    val nearbySignals: StateFlow<List<EchoTrackSignal>> = _nearbySignals.asStateFlow()

    val cityZones = listOf(
        CityZoneTrace("Парк Горького · Набережная", "Ambient / Post-Rock", "Hammock, Sigur Rós, Brian Eno", 14),
        CityZoneTrace("Сретенка · Камерные бары", "Post-Punk / Darkwave", "Молчат Дома, Lebanon Hanover, The Cure", 9),
        CityZoneTrace("Покровка · Ночные кофейни", "Trip-Hop / Nu-Jazz", "Portishead, Bohren & der Club of Gore", 11),
        CityZoneTrace("Университетский квартал", "Midwest Emo / Shoegaze", "Slowdive, American Football, Alcest", 18)
    )

    val eveningHistory = listOf(
        "21:40 · Рядом прозвучал Coil — The Ape of Naples (~90 м)",
        "21:15 · Повторная встреча (3-й раз): слушатель Burial — Archangel",
        "20:50 · Музыкальный резонанс в парке: Dead Can Dance — Host of Seraphim",
        "19:30 · Вечерний след у метро: Aphex Twin — #3 (Rhubarb)"
    )

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun togglePresence() {
        _presenceEnabled.value = !_presenceEnabled.value
    }

    fun updateMyPlayingTrack(artist: String, title: String, sourcePackage: String) {
        val cleanArtist = artist.trim().ifEmpty { "Неизвестный исполнитель" }
        val cleanTitle = title.trim().ifEmpty { "Без названия" }
        val readableSource = mapPackageToName(sourcePackage)
        val curated = inferPersonaFromTrack(cleanArtist, cleanTitle)

        _myCurrentTrack.value = _myCurrentTrack.value.copy(
            artist = cleanArtist,
            title = cleanTitle,
            sourceName = readableSource,
            personaTitle = curated.first,
            moodLabel = curated.second,
            personaObservation = curated.third,
            avatarPetals = ((cleanArtist.length + cleanTitle.length) % 6) + 5
        )
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
    }

    private fun inferPersonaFromTrack(artist: String, title: String): Triple<String, String String,> {
        val combined = "$artist $title".lowercase()
        return when {
            combined.contains("wardruna") || combined.contains("agalloch") || combined.contains("forest") || combined.contains("лес") ->
                Triple(
                    "Хранитель северных лесов",
                    "Эпическое созерцание",
                    "ИИ-куратор: вы выбираете глубокие природные текстуры и фолк-атмосферу."
                )
            combined.contains("burial") || combined.contains("massive") || combined.contains("night") || combined.contains("ночь") ->
                Triple(
                    "Проводник ночных туманов",
                    "Городской даб и тени",
                    "ИИ-куратор: в вашем эфире преобладает обволакивающий бас и ночная кинематографичность."
                )
            combined.contains("radiohead") || combined.contains("post") || combined.contains("ambient") ->
                Triple(
                    "Архивариус забытых кассет",
                    "Тонкая меланхолия",
                    "ИИ-куратор: вы цените сложную гармонию и медленное эмоциональное раскрытие."
                )
            else ->
                Triple(
                    "Искатель звуковых горизонтов",
                    "Живой городской эфир",
                    "ИИ-куратор зафиксировал волну «$artist» и обновил ваш радиус резонанса."
                )
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
