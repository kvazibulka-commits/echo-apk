package ru.echo.radar

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EchoStateRepository.initLocalStore(this)
        setContent {
            EchoRadarApp()
        }
    }
}

private fun isNotificationListenerGranted(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return flat.contains(context.packageName)
}

private fun ensureServiceBound(context: Context) {
    try {
        NotificationListenerService.requestRebind(
            ComponentName(context, EchoMediaListenerService::class.java)
        )
    } catch (_: Exception) {
    }
}

/**
 * Открывает трек в установленном плеере (Яндекс Музыка, VK Музыка, Звук, Poweramp)
 * без платных API и без VPN.
 */
private fun openTrackInRussianPlayer(context: Context, artist: String, title: String) {
    val query = "$artist — $title".trim()
    val encoded = Uri.encode("$artist $title")

    // 1. Прямой запуск поиска/воспроизведения через стандартный медиа-интент Android
    try {
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, "$artist $title")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (playIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(playIntent)
            Toast.makeText(context, "Открываем в плеере: $query", Toast.LENGTH_SHORT).show()
            return
        }
    } catch (_: Exception) {
    }

    // 2. Переход в приложение или веб-версию Яндекс Музыки (работает в РФ без VPN)
    try {
        val yandexUrl = Uri.parse("https://music.yandex.ru/search?text=$encoded")
        val webIntent = Intent(Intent.ACTION_VIEW, yandexUrl).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
        Toast.makeText(context, "Поиск волны в Яндекс Музыке: $query", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "Не найден плеер для открытия трека", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EchoRadarApp() {
    val context = LocalContext.current
    val presenceEnabled by EchoStateRepository.presenceEnabled.collectAsState()
    val myTrack by EchoStateRepository.myCurrentTrack.collectAsState()
    val nearbySignals by EchoStateRepository.nearbySignals.collectAsState()
    val listenerConnected by EchoStateRepository.listenerConnected.collectAsState()
    val savedTracks by EchoStateRepository.savedTracks.collectAsState()
    val incomingBanner by EchoStateRepository.incomingBanner.collectAsState()
    val eveningTraces by EchoStateRepository.eveningTraces.collectAsState()

    var hasOsPermission by remember { mutableStateOf(isNotificationListenerGranted(context)) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Радар, 1 = Поток, 2 = Следы и Находки
    var selectedSignalId by remember { mutableStateOf(nearbySignals.firstOrNull()?.id ?: "bot_1") }
    var showManualDialog by remember { mutableStateOf(false) }

    val selectedSignal = nearbySignals.find { it.id == selectedSignalId } ?: nearbySignals.first()
    val isSelectedSaved = savedTracks.any {
        it.artist.equals(selectedSignal.artist, ignoreCase = true) &&
            it.title.equals(selectedSignal.title, ignoreCase = true)
    }

    // Авто-проверка системного разрешения на уведомления
    LaunchedEffect(Unit) {
        while (true) {
            val granted = isNotificationListenerGranted(context)
            hasOsPermission = granted
            if (granted) {
                ensureServiceBound(context)
            }
            delay(2000)
        }
    }

    // Авто-скрытие всплывающего баннера входящего сигнала через 6 секунд
    LaunchedEffect(incomingBanner?.id) {
        if (incomingBanner != null) {
            delay(6000)
            EchoStateRepository.dismissBanner()
        }
    }

    val testPresets = remember {
        listOf(
            Triple("Яндекс Музыка", "Молчат Дома", "Судно (Борис Рыжий)"),
            Triple("VK Музыка", "Wardruna", "Kvitravn"),
            Triple("Poweramp", "Blackmore's Night", "Shadow Of The Moon"),
            Triple("Звук", "Кино", "Спокойная ночь")
        )
    }
    var presetIndex by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF070B14)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Шапка приложения
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Э  Х  О",
                        color = Color(0xFFF1F5F9),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Ты видишь музыку, которая живет вокруг тебя",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (presenceEnabled) Color(0xFF0F2942) else Color(0xFF1E293B))
                        .border(
                            1.dp,
                            if (presenceEnabled) Color(0xFF38BDF8) else Color(0xFF475569),
                            RoundedCornerShape(20.dp)
                        )
                        .clickable { EchoStateRepository.togglePresence() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (presenceEnabled) "● Присутствие: Вкл" else "○ Присутствие: Скрыто",
                        color = if (presenceEnabled) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Всплывающее уведомление, когда кто-то из эфира прислал реакцию на ваш трек
            incomingBanner?.let { banner ->
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E1B4B))
                        .border(1.dp, Color(0xFFA78BFA), RoundedCornerShape(14.dp))
                        .clickable { EchoStateRepository.dismissBanner() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = banner.symbol, fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ВХОДЯЩИЙ СИГНАЛ ИЗ ЭФИРА",
                                color = Color(0xFFA78BFA),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = banner.message,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Text(
                        text = "✕",
                        color = Color(0xFF94A3B8),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Блок текущего эфира пользователя
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "В ВАШИХ НАУШНИКАХ · ${myTrack.sourceName.uppercase()}",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    val statusColor = if (hasOsPermission || listenerConnected) Color(0xFF34D399) else Color(0xFFF59E0B)
                    val statusText = if (hasOsPermission || listenerConnected) {
                        "● Перехват ОС активен"
                    } else {
                        "○ Нужен доступ к уведомлениям"
                    }
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    GenerativeAvatarCanvas(
                        color = Color(myTrack.colorHex),
                        petals = myTrack.avatarPetals,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "♫ ${myTrack.artist} — ${myTrack.title}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ваш образ ИИ: ${myTrack.personaTitle} · ${myTrack.moodLabel}",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        Text(
                            text = myTrack.personaObservation,
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }
                }

                // Отображение полученных реакций от других людей на вашу музыку
                if (myTrack.reactions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF111827))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Отклики рядом на вашу волну: ",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        myTrack.reactions.forEach { (sym, cnt) ->
                            Text(
                                text = "$sym $cnt   ",
                                color = Color(0xFF38BDF8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(
                            1.dp,
                            if (hasOsPermission) Color(0xFF334155) else Color(0xFF38BDF8)
                        )
                    ) {
                        Text(
                            text = if (hasOsPermission) "Настройки доступа ОС" else "Включить перехват ОС",
                            color = if (hasOsPermission) Color(0xFFCBD5E1) else Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val p = testPresets[presetIndex % testPresets.size]
                            presetIndex++
                            EchoStateRepository.updateMyPlayingTrack(
                                artist = p.second,
                                title = p.third,
                                sourcePackage = p.first
                            )
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Text(
                            text = "Тест-перехват ♫",
                            color = Color(0xFF34D399),
                            fontSize = 12.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { showManualDialog = true },
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Text(
                            text = "Вручную",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Переключатель режимов (Радар / Поток / Следы и Находки)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val savedBadge = if (savedTracks.isNotEmpty()) " (${savedTracks.size})" else ""
                val tabs = listOf(
                    "◎ Музыкальный радар",
                    "≋ Поток сигналов",
                    "✦ Следы и находки$savedBadge"
                )
                tabs.forEachIndexed { idx, title ->
                    val active = selectedTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Color(0xFF1E293B) else Color.Transparent)
                            .clickable { selectedTab = idx }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (active) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (selectedTab) {
                0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF0A101F))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Звуковое поле вокруг вас (радиус ~300 м · координаты размыты)",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${nearbySignals.size} в эфире",
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        InteractiveRadarField(
                            signals = nearbySignals,
                            selectedSignalId = selectedSignal.id,
                            onSelectSignal = { selectedSignalId = it.id }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Нажмите на точку на радаре или на звучащую композицию ниже:",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            nearbySignals.forEach { sig ->
                                val isSel = sig.id == selectedSignal.id
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(if (isSel) Color(0xFF172554) else Color(0xFF111827))
                                        .border(
                                            1.dp,
                                            if (isSel) Color(sig.colorHex) else Color(0xFF1E293B),
                                            RoundedCornerShape(20.dp)
                                        )
                                        .clickable { selectedSignalId = sig.id }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "♫ ${sig.artist} — ${sig.title} (~${sig.distanceMeters} м)",
                                        color = if (isSel) Color.White else Color(0xFFCBD5E1),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF0A101F))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Медленный поток композиций рядом (сначала музыка, затем образ):",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )

                        nearbySignals.forEach { sig ->
                            val isSelected = sig.id == selectedSignal.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSelected) Color(0xFF172554) else Color(0xFF111827))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(sig.colorHex) else Color(0xFF1E293B),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { selectedSignalId = sig.id }
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "♫ ${sig.artist} — ${sig.title}",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "${sig.moodLabel} · ${sig.sourceName} · Резонанс ${sig.resonanceDays} дн.",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = "~${sig.distanceMeters} м",
                                    color = Color(sig.colorHex),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF0A101F))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Раздел сохранённых треков из эфира
                        Text(
                            text = "ПОЙМАННЫЕ В ЭФИРЕ НАХОДКИ (СОХРАНЁННЫЕ ТРЕКИ)",
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (savedTracks.isEmpty()) {
                            Text(
                                text = "Пока пусто. Нажмите «★ Сохранить трек» в карточке любого слушателя ниже, чтобы добавить песню в коллекцию.",
                                color = Color(0xFF64748B),
                                fontSize = 12.sp
                            )
                        } else {
                            savedTracks.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF111827))
                                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "♫ ${item.artist} — ${item.title}",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Поймано в ${item.savedAt} от «${item.personaTitle}» (${item.moodLabel})",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF0F2942))
                                                .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    openTrackInRussianPlayer(context, item.artist, item.title)
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text("♫ Слушать", color = Color(0xFF38BDF8), fontSize = 11.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1E293B))
                                                .clickable { EchoStateRepository.removeSavedTrack(item) }
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text("✕", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "КАРТА ЗВУЧАНИЯ ГОРОДА (АНОНИМНАЯ АГРЕГАЦИЯ МЕСТ)",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        EchoStateRepository.cityZones.forEach { zone ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF111827))
                                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = zone.placeName,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = zone.dominantVibe,
                                        color = Color(0xFF34D399),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Звучат: ${zone.recentArtists} · ${zone.listenersCount} сигналов за вечер",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "СЕГОДНЯ РЯДОМ С ТОБОЙ ЗВУЧАЛИ (ВЕЧЕРНИЙ ДНЕВНИК)",
                            color = Color(0xFFA78BFA),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        eveningTraces.forEach { trace ->
                            Text(
                                text = "• $trace",
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Карточка выбранного слушателя (Музыкальный профиль + Кнопки взаимодействия)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GenerativeAvatarCanvas(
                        color = Color(selectedSignal.colorHex),
                        petals = selectedSignal.avatarPetals,
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "МУЗЫКАЛЬНЫЙ ОБРАЗ (ИИ-КУРАТОР)",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = selectedSignal.personaTitle,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Совместимость: ${selectedSignal.compatibilityPercent}% · ${selectedSignal.sourceName} · Настроение: ${selectedSignal.moodLabel}",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E1B4B))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "✦ Музыкальный резонанс · ${selectedSignal.resonanceDays} дней в общей музыкальной фазе · Встреча №${selectedSignal.encountersCount}",
                        color = Color(0xFFC4B5FD),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Сейчас звучит: ${selectedSignal.artist} — ${selectedSignal.title}",
                    color = Color(0xFFF1F5F9),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "«${selectedSignal.personaObservation}»",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 3 основные музыкальные кнопки карточки (по концепции)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Совместное прослушивание / Открыть в плеере (Яндекс Музыка / Системный плеер)
                    Button(
                        onClick = {
                            openTrackInRussianPlayer(
                                context = context,
                                artist = selectedSignal.artist,
                                title = selectedSignal.title
                            )
                        },
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text(
                            text = "♫ Слушать волну",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 2. Сохранить трек в находки
                    OutlinedButton(
                        onClick = {
                            val added = EchoStateRepository.saveTrackToFinds(selectedSignal)
                            if (added) {
                                Toast.makeText(
                                    context,
                                    "Трек сохранён во вкладку «Следы и находки»",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                selectedTab = 2
                            }
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelectedSaved) Color(0xFF34D399) else Color(0xFF38BDF8)
                        )
                    ) {
                        Text(
                            text = if (isSelectedSaved) "✓ В находках" else "★ Сохранить трек",
                            color = if (isSelectedSaved) Color(0xFF34D399) else Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                    }

                    // 3. Отправить музыкальный сигнал присутствия
                    OutlinedButton(
                        onClick = {
                            EchoStateRepository.sendSymbolReaction(selectedSignal.id, "✦")
                            Toast.makeText(
                                context,
                                "Музыкальный сигнал присутствия ✦ отправлен «${selectedSignal.personaTitle}»",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        border = BorderStroke(1.dp, Color(0xFFA78BFA))
                    ) {
                        Text(
                            text = "〰 Сигнал",
                            color = Color(0xFFC4B5FD),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Передать атмосферный отклик (вместо лайков и оценок):",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                val symbols = listOf("🦇", "🌑", "🌊", "⚔", "🌲", "🎼", "☁")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    symbols.forEach { sym ->
                        val count = selectedSignal.reactions[sym] ?: 0
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                                .border(1.dp, Color(0xFF334155), CircleShape)
                                .clickable {
                                    EchoStateRepository.sendSymbolReaction(selectedSignal.id, sym)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (count > 0) "$sym $count" else sym,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        var artistInput by remember { mutableStateOf("") }
        var titleInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            containerColor = Color(0xFF0F172A),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            title = { Text("Ручной сигнал или IP сервера ПК") },
            text = {
                Column {
                    OutlinedTextField(
                        value = artistInput,
                        onValueChange = { artistInput = it },
                        label = { Text("Исполнитель (или IP вашего ПК)") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Композиция") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (artistInput.isNotBlank() || titleInput.isNotBlank()) {
                            EchoStateRepository.updateMyPlayingTrack(
                                artist = artistInput,
                                title = titleInput,
                                sourcePackage = "manual"
                            )
                        }
                        showManualDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("Транслировать в эфир")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Отмена", color = Color(0xFF94A3B8))
                }
            }
        )
    }
}

@Composable
fun InteractiveRadarField(
    signals: List<EchoTrackSignal>,
    selectedSignalId: String,
    onSelectSignal: (EchoTrackSignal) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "node_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(270.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(260.dp)
                .pointerInput(signals) {
                    detectTapGestures { tapOffset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val maxR = (size.width.coerceAtMost(size.height) / 2f) * 0.88f

                        val tapped = signals.minByOrNull { sig ->
                            val rad = sig.angleDegrees * (PI.toFloat() / 180f)
                            val r = maxR * sig.distanceRatio
                            val sx = cx + r * cos(rad)
                            val sy = cy + r * sin(rad)
                            hypot(tapOffset.x - sx, tapOffset.y - sy)
                        }
                        if (tapped != null) {
                            onSelectSignal(tapped)
                        }
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = (size.minDimension / 2f) * 0.88f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0F2942), Color(0xFF070B14)),
                    center = Offset(cx, cy),
                    radius = maxR
                ),
                radius = maxR,
                center = Offset(cx, cy)
            )

            listOf(0.33f, 0.66f, 1.0f).forEach { frac ->
                drawCircle(
                    color = Color(0xFF1E293B),
                    radius = maxR * frac,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f)
                )
            }

            drawLine(
                color = Color(0xFF1E293B),
                start = Offset(cx - maxR, cy),
                end = Offset(cx + maxR, cy),
                strokeWidth = 1f
            )
            drawLine(
                color = Color(0xFF1E293B),
                start = Offset(cx, cy - maxR),
                end = Offset(cx, cy + maxR),
                strokeWidth = 1f
            )

            val sweepRad = sweepAngle * (PI.toFloat() / 180f)
            val beamEnd = Offset(
                cx + maxR * cos(sweepRad),
                cy + maxR * sin(sweepRad)
            )
            drawLine(
                color = Color(0xFF38BDF8).copy(alpha = 0.45f),
                start = Offset(cx, cy),
                end = beamEnd,
                strokeWidth = 2.5f
            )

            drawCircle(
                color = Color(0xFF38BDF8).copy(alpha = 0.25f),
                radius = 18f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color(0xFF38BDF8),
                radius = 7f,
                center = Offset(cx, cy)
            )

            signals.forEach { sig ->
                val rad = sig.angleDegrees * (PI.toFloat() / 180f)
                val r = maxR * sig.distanceRatio
                val sx = cx + r * cos(rad)
                val sy = cy + r * sin(rad)
                val isSelected = sig.id == selectedSignalId
                val nodeColor = Color(sig.colorHex)

                if (isSelected) {
                    drawLine(
                        color = nodeColor.copy(alpha = 0.55f),
                        start = Offset(cx, cy),
                        end = Offset(sx, sy),
                        strokeWidth = 2f
                    )
                    drawCircle(
                        color = nodeColor.copy(alpha = 0.28f),
                        radius = 22f * pulse,
                        center = Offset(sx, sy)
                    )
                }

                drawCircle(
                    color = nodeColor.copy(alpha = 0.2f),
                    radius = 13f,
                    center = Offset(sx, sy)
                )
                drawCircle(
                    color = if (isSelected) Color.White else nodeColor,
                    radius = if (isSelected) 8.5f else 6.5f,
                    center = Offset(sx, sy)
                )
            }
        }
    }
}

@Composable
fun GenerativeAvatarCanvas(
    color: Color,
    petals: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_pulse")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = (size.minDimension / 2f) * 0.68f

        drawCircle(
            color = color.copy(alpha = 0.16f),
            radius = baseR * 1.2f,
            center = Offset(cx, cy)
        )

        val path = Path()
        val steps = 72
        for (i in 0..steps) {
            val angle = (i.toFloat() / steps) * (2f * PI.toFloat())
            val wave = sin(angle * petals + phase) * (baseR * 0.22f)
            val r = (baseR + wave).coerceAtLeast(2f)
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        drawPath(
            path = path,
            color = color.copy(alpha = 0.42f)
        )
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.2f)
        )
    }
}
