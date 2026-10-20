package ru.echo.radar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

object EchoStateRepository {

    private val _presenceEnabled = MutableStateFlow(true)
    val presenceEnabled: StateFlow<Boolean> = _presenceEnabled.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    // Память прослушивания за сессию для анализа динамики вкуса
    private val sessionTrackHistory = mutableListOf<Pair<String, String>>()
    private var sameArtistStreak = 1
    private var currentArchetypeSeed = 0

    // Палитра из 20 контрастных атмосферных оттенков для генеративного аватара
    private val avatarColorPalette = listOf(
        0xFF38BDF8L, // Неоновый циан
        0xFFA78BFAL, // Глубокий аметист
        0xFF34D399L, // Северное сияние (изумруд)
        0xFFF472B6L, // Ночной розовый неон
        0xFFFBBF24L, // Теплый ламповый янтарь
        0xFF60A5FAL, // Ледяной кобальт
        0xFFFB7185L, // Багровый закат
        0xFF2DD4BFL, // Морская бездна (бирюза)
        0xFF818CF8L, // Индиго сумерек
        0xFFC084FCL, // Фиолетовый эфир
        0xFF4ADE80L, // Лесной мох
        0xFFF97316L, // Огненный импульс
        0xFFE879F9L, // Кибер-пурпур
        0xFF22D3EEL, // Кристальный лед
        0xFFA3E635L, // Кислотный лайм
        0xFFEF4444L, // Рубиновый лазер
        0xFF93C5FDL, // Туманный рассвет
        0xFFD8B4FEL, // Лавандовый дым
        0xFF6EE7B7L, // Мятный бриз
        0xFFFDA4AFL  // Розовый кварц
    )

    // Матрица для генерации 36 000+ уникальных литературных образов
    private val archetypes = listOf(
        "Архивариус", "Проводник", "Хранитель", "Навигатор", "Алхимик",
        "Созерцатель", "Картограф", "Ловец", "Хроникёр", "Часовой",
        "Странник", "Дешифровщик", "Смотритель", "Собиратель", "Реставратор",
        "Слушатель", "Сталкер", "Искатель", "Пилот", "Куратор",
        "Механик", "Призрак", "Свидетель", "Астроном", "Дирижёр",
        "Переводчик", "Охотник", "Исследователь", "Поэт", "Часовщик"
    )

    private val epithets = listOf(
        "забытых", "ночных", "туманных", "неоновых", "северных",
        "полуночных", "аналоговых", "призрачных", "хрустальных", "подземных",
        "дождливых", "меланхоличных", "индустриальных", "звёздных", "сумеречных",
        "бархатных", "ветреных", "электрических", "далёких", "заснеженных",
        "тихих", "глубоких", "винтажных", "бесконечных", "скрытых",
        "мерцающих", "холодных", "ламповых", "космических", "лесных",
        "городских", "пульсирующих", "осенних", "зеркальных", "невесомых"
    )

    private val realms = listOf(
        "кассет", "шорохов", "частот", "сновидений", "кварталов",
        "лесов", "горизонтов", "пластинок", "созвездий", "мостов",
        "теней", "радиоволн", "лабиринтов", "крыш", "переулков",
        "миражей", "приливов", "эхолотов", "сумерек", "винила",
        "маршрутов", "сигналов", "океанов", "орбит", "воспоминаний",
        "резонансов", "импульсов", "туманов", "симфоний", "станций",
        "отголосков", "механических снов", "магнитных лент", "огней", "пустот"
    )

    private val moodLabels = listOf(
        "Ночное созерцание", "Холодный пост-панк и неон", "Тёплая аналоговая меланхолия",
        "Северный ритуал", "Городской даб и тени", "Электрический драйв",
        "Кинематографичный эмбиент", "Гитарная невесомость", "Индустриальный пульс",
        "Бархатный грув", "Сумеречный фолк", "Космическая одиссея",
        "Дождливый лоу-фай", "Акустическая исповедь", "Тяжёлый катарсис",
        "Гипнотический минимализм"
    )

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
                colorHex = 0xFF38BDF8L,
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
                colorHex = 0xFF34D399L,
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
                colorHex = 0xFFA78BFAL,
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
                colorHex = 0xFFF472B6L,
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
                colorHex = 0xFF60A5FAL,
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

    fun updateDetectedMusic(artist: String, title: String, sourcePackage: String) {
        updateMyPlayingTrack(artist, title, sourcePackage)
    }

    fun updateMyPlayingTrack(artist: String, title: String, sourcePackage: String) {
        val cleanArtist = artist.trim().ifEmpty { "Неизвестный исполнитель" }
        val cleanTitle = title.trim().ifEmpty { "Без названия" }

        val current = _myCurrentTrack.value
        // Игнорируем повторные системные уведомления от того же самого трека (например, тик таймера в Яндекс Музыке)
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

        val readableSource = mapPackageToName(sourcePackage)
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
            sourceName = readableSource,
            personaTitle = curated.personaTitle,
            moodLabel = curated.moodLabel,
            personaObservation = curated.observation,
            colorHex = curated.colorHex,
            avatarPetals = curated.petals
        )

        // Пересчитываем музыкальную совместимость с людьми вокруг под текущий вектор музыки
        recalculateNearbyCompatibility(curated.vibeVector)
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

        // Глубокий хеш исполнителя и композиции для уникальной сигнатуры
        val artistHash = abs(artistNorm.hashCode() * 31 + artistNorm.sumOf { it.code * 17 })
        val titleHash = abs(titleNorm.hashCode() * 37 + titleNorm.sumOf { it.code * 13 })
        val combinedHash = abs(fullKey.hashCode() xor (artistHash + titleHash))

        // Если сменился исполнитель — формируем новое уникальное ядро образа.
        // Если играет тот же исполнитель подряд — сохраняем ядро архетипа, но развиваем оттенок.
        if (!isSameArtist || currentArchetypeSeed == 0) {
            currentArchetypeSeed = artistHash
        }

        val archetypeIndex = currentArchetypeSeed % archetypes.size
        // Эпитет и метафора зависят от сочетания артиста и характера самой песни
        val epithetIndex = if (isSameArtist && streak < 3) {
            (currentArchetypeSeed / 7) % epithets.size
        } else {
            (combinedHash / 5) % epithets.size
        }
        val realmIndex = (currentArchetypeSeed / 13 + (if (streak >= 3) titleHash else 0)) % realms.size

        val generatedTitle = "${archetypes[archetypeIndex]} ${epithets[epithetIndex]} ${realms[realmIndex]}"
        val mood = detectMoodOrFallback(artistNorm, titleNorm, combinedHash)

        // Выбираем контрастный цвет и число граней (от 3 до 14), чтобы аватар заметно менялся на каждом треке
        val colorIdx = (combinedHash + totalTracksInSession * 3) % avatarColorPalette.size
        val selectedColor = avatarColorPalette[colorIdx]
        val petals = 3 + (combinedHash % 12) // от 3 (треугольная волна) до 14 (лучевая звезда)

        val observation = when {
            previousArtist == null ->
                "ИИ-куратор настроил ваш первичный эфир на волну «$artist». Образ: $generatedTitle ($mood)."
            isSameArtist && streak == 2 ->
                "Вы остаётесь на волне «$artist» (2-й трек подряд). Образ «$generatedTitle» закрепляется, аватар меняет гармонический рисунок."
            isSameArtist && streak >= 3 ->
                "Глубокое погружение в «$artist» ($streak-я композиция подряд). Ваша музыкальная фаза стабильна, спектр сместился к оттенку «$mood»."
            else ->
                "Смена волны с «$previousArtist» на «$artist»: звучание развернулось в сторону «$mood». ИИ-куратор сформировал новый образ (треков за сессию: $totalTracksInSession)."
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
        val text = "$artistNorm $titleNorm"
        return when {
            text.contains("кино") || text.contains("молчат") || text.contains("cure") || text.contains("дурной") || text.contains("post") ->
                "Холодный пост-панк и неон"
            text.contains("wardruna") || text.contains("agalloch") || text.contains("heilung") || text.contains("фолк") || text.contains("лес") ->
                "Северный ритуал и туман"
            text.contains("burial") || text.contains("massive") || text.contains("portishead") || text.contains("tricky") ->
                "Ночной трип-хоп и винил"
            text.contains("aphex") || text.contains("ambient") || text.contains("eno") || text.contains("coil") || text.contains("drone") ->
                "Глубокий эмбиент-эфир"
            text.contains("metallica") || text.contains("doom") || text.contains("black") || text.contains("slayer") || text.contains("metal") ->
                "Тяжёлый штормовой рифф"
            text.contains("radiohead") || text.contains("muse") || text.contains("placebo") || text.contains("interpol") ->
                "Гитарная меланхолия"
            text.contains("ночь") || text.contains("night") || text.contains("dark") || text.contains("тень") || text.contains("сон") ->
                "Полуночное созерцание"
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
