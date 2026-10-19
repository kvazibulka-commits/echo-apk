package ru.echo.radar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayingTrack(
    val artist: String,
    val title: String,
    val sourceApp: String,
    val poeticPersona: String,
    val mood: String
)

data class NearbyEchoSignal(
    val id: String,
    val artist: String,
    val title: String,
    val sourceApp: String,
    val distanceLabel: String,
    val poeticName: String,
    val aiObservation: String,
    val compatibility: Int,
    val resonanceDays: String?,
    val angleDeg: Float,
    val ringFactor: Float
)

object EchoStateRepository {

    private val _currentTrack = MutableStateFlow(
        PlayingTrack(
            artist = "Ожидание музыки...",
            title = "Включите трек в Яндекс Музыке / VK / Плеере",
            sourceApp = "Системный перехват Android",
            poeticPersona = "Архивариус ночных шорохов",
            mood = "Ночная тишина"
        )
    )
    val currentTrack: StateFlow<PlayingTrack> = _currentTrack.asStateFlow()

    private val _presenceActive = MutableStateFlow(true)
    val presenceActive: StateFlow<Boolean> = _presenceActive.asStateFlow()

    val nearbySignals = listOf(
        NearbyEchoSignal(
            id = "sig_1",
            artist = "Radiohead",
            title = "Weird Fishes/Arpeggi",
            sourceApp = "Яндекс Музыка",
            distanceLabel = "~120 м (размыто)",
            poeticName = "Проводник туманов",
            aiObservation = "Собирает ускользающие гитарные арпеджио и ночную электронику, где ритм звучит как шаги по мокрому асфальту.",
            compatibility = 92,
            resonanceDays = "Музыкальный резонанс · 3 недели",
            angleDeg = 38f,
            ringFactor = 0.52f
        ),
        NearbyEchoSignal(
            id = "sig_2",
            artist = "Burial",
            title = "Archangel",
            sourceApp = "Локальный FLAC (Poweramp)",
            distanceLabel = "~90 м (размыто)",
            poeticName = "Архивариус забытых кассет",
            aiObservation = "Ночами слушает лондонский тустеп и призрачный эмбиент с шорохом винила.",
            compatibility = 96,
            resonanceDays = "Музыкальный резонанс · 19 дней",
            angleDeg = 210f,
            ringFactor = 0.45f
        ),
        NearbyEchoSignal(
            id = "sig_3",
            artist = "Wardruna",
            title = "Helvegen",
            sourceApp = "VK Музыка",
            distanceLabel = "~190 м (размыто)",
            poeticName = "Хранитель северных лесов",
            aiObservation = "Выбирает архаичную перкуссию, древние скандинавские напевы и акустическое эхо.",
            compatibility = 85,
            resonanceDays = null,
            angleDeg = 135f,
            ringFactor = 0.72f
        ),
        NearbyEchoSignal(
            id = "sig_4",
            artist = "Massive Attack",
            title = "Angel",
            sourceApp = "Звук",
            distanceLabel = "~240 м (размыто)",
            poeticName = "Меланхоличный космонавт",
            aiObservation = "Тяготеет к глубоким басовым линиям бристольского трип-хопа и кинематографичному саспенсу.",
            compatibility = 89,
            resonanceDays = "Музыкальный резонанс · 11 дней",
            angleDeg = 310f,
            ringFactor = 0.82f
        )
    )

    fun updateDetectedMusic(artist: String, title: String, packageName: String) {
        val readableSource = when {
            packageName.contains("yandex", ignoreCase = true) -> "Яндекс Музыка"
            packageName.contains("vkontakte", ignoreCase = true) || packageName.contains("vk.music", ignoreCase = true) -> "VK Музыка"
            packageName.contains("zvuk", ignoreCase = true) || packageName.contains("sber", ignoreCase = true) -> "Звук"
            packageName.contains("spotify", ignoreCase = true) -> "Spotify"
            packageName.contains("telegram", ignoreCase = true) -> "Telegram Плеер"
            packageName.contains("aimp", ignoreCase = true) -> "AIMP"
            packageName.contains("poweramp", ignoreCase = true) -> "Poweramp"
            else -> "Плеер Android ($packageName)"
        }

        val persona = when {
            artist.length % 3 == 0 -> "Проводник туманных частот"
            artist.length % 2 == 0 -> "Архивариус ночных шорохов"
            else -> "Собиратель вечернего эха"
        }

        _currentTrack.value = PlayingTrack(
            artist = artist.ifBlank { "Неизвестный исполнитель" },
            title = title.ifBlank { "Без названия" },
            sourceApp = readableSource,
            poeticPersona = persona,
            mood = "Живое присутствие"
        )
    }

    fun togglePresence() {
        _presenceActive.value = !_presenceActive.value
    }
}
