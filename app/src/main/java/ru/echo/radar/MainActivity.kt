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

private fun openTrackInRussianPlayer(context: Context, artist: String, title: String) {
    val query = "$artist — $title".trim()
    val encoded = Uri.encode("$artist $title")

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
    val liveZones by EchoStateRepository.liveCityZones.collectAsState()
    val myCurrentZone by EchoStateRepository.myCurrentZone.collectAsState()

    var hasOsPermission by remember { mutableStateOf(isNotificationListenerGranted(context)) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    var showSettingsModal by remember { mutableStateOf(false) }

    val activeProfileSignal = nearbySignals.find { it.id == activeProfileId }

    LaunchedEffect(Unit) {
        while (true) {
            val granted = isNotificationListenerGranted(context)
            hasOsPermission = granted
            if (granted) ensureServiceBound(context)
            delay(2000)
        }
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Э  Х  О",
                        color = Color(0xFFF1F5F9),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "📍 $myCurrentZone",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showSettingsModal = true }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = if (presenceEnabled) "● В эфире" else "○ Скрыт",
                            color = if (presenceEnabled) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A))
                            .border(
                                1.dp,
                                if (hasOsPermission || listenerConnected) Color(0xFF1E293B) else Color(0xFFF59E0B),
                                CircleShape
                            )
                            .clickable { showSettingsModal = true }
                            .padding(horizontal = 11.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "⚙",
                            color = if (hasOsPermission || listenerConnected) Color(0xFF94A3B8) else Color(0xFFF59E0B),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            incomingBanner?.let { banner ->
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E1B4B))
                        .border(1.dp, Color(0xFFA78BFA), RoundedCornerShape(14.dp))
                        .clickable { EchoStateRepository.dismissBanner() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${banner.symbol}  ${banner.message}",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = "✕", color = Color(0xFF94A3B8), fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                    .clickable { showSettingsModal = true }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GenerativeAvatarCanvas(
                    color = Color(myTrack.colorHex),
                    petals = myTrack.avatarPetals,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "♫ ${myTrack.artist} — ${myTrack.title}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${myTrack.personaTitle} · ${myTrack.moodLabel}",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (myTrack.reactions.isNotEmpty()) {
                        val reactLine = myTrack.reactions.entries.joinToString("  ") { "${it.key} ${it.value}" }
                        Text(
                            text = "Отклики рядом: $reactLine",
                            color = Color(0xFFA78BFA),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val savedBadge = if (savedTracks.isNotEmpty()) " (${savedTracks.size})" else ""
                val tabs = listOf("◎ Радар", "≋ Поток", "✦ Следы$savedBadge")
                tabs.forEachIndexed { idx, title ->
                    val active = selectedTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) Color(0xFF1E293B) else Color.Transparent)
                            .clickable { selectedTab = idx }
                            .padding(vertical = 8.dp),
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

            Spacer(modifier = Modifier.height(12.dp))

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
                        InteractiveRadarField(
                            signals = nearbySignals,
                            selectedSignalId = activeProfileId ?: "",
                            onSelectSignal = { activeProfileId = it.id }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Музыка вокруг вас (нажмите на песню, чтобы почувствовать слушателя):",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            nearbySignals.forEach { sig ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF111827))
                                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                                        .clickable { activeProfileId = sig.id }
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .clip(CircleShape)
                                                .background(Color(sig.colorHex))
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "♫ ${sig.artist} — ${sig.title}",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "~${sig.distanceMeters} м",
                                        color = Color(0xFF64748B),
                                        fontSize = 12.sp
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
                        nearbySignals.forEach { sig ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF111827))
                                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                                    .clickable { activeProfileId = sig.id }
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
                                        text = "${sig.moodLabel} · Резонанс ${sig.resonanceDays} дн.",
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
                        if (savedTracks.isNotEmpty()) {
                            Text(
                                text = "СОХРАНЁННЫЕ НАХОДКИ",
                                color = Color(0xFF34D399),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            savedTracks.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF111827))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "♫ ${item.artist} — ${item.title}",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${item.personaTitle} · ${item.savedAt}",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "♫ Слушать",
                                            color = Color(0xFF38BDF8),
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .clickable { openTrackInRussianPlayer(context, item.artist, item.title) }
                                                .padding(6.dp)
                                        )
                                        Text(
                                            text = "✕",
                                            color = Color(0xFF64748B),
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .clickable { EchoStateRepository.removeSavedTrack(item) }
                                                .padding(6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = "КАРТА ЗВУЧАНИЯ ГОРОДА",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        liveZones.forEach { zone ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF111827))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = zone.placeName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(text = zone.dominantVibe, color = Color(0xFF34D399), fontSize = 11.sp)
                                }
                                Text(
                                    text = "${zone.recentArtists} · ${zone.listenersCount} сигн.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Text(
                            text = "ВЕЧЕРНИЙ ДНЕВНИК СЛЕДОВ",
                            color = Color(0xFFA78BFA),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        eveningTraces.forEach { trace ->
                            Text(text = "• $trace", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    if (activeProfileSignal != null) {
        val isSaved = savedTracks.any {
            it.artist.equals(activeProfileSignal.artist, ignoreCase = true) &&
                it.title.equals(activeProfileSignal.title, ignoreCase = true)
        }

        AlertDialog(
            onDismissRequest = { activeProfileId = null },
            containerColor = Color(0xFF0F172A),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GenerativeAvatarCanvas(
                        color = Color(activeProfileSignal.colorHex),
                        petals = activeProfileSignal.avatarPetals,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = activeProfileSignal.personaTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Совместимость: ${activeProfileSignal.compatibilityPercent}% · ~${activeProfileSignal.distanceMeters} м",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "♫ ${activeProfileSignal.artist} — ${activeProfileSignal.title}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Настроение: ${activeProfileSignal.moodLabel} (${activeProfileSignal.sourceName})",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E1B4B))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "✦ Резонанс: ${activeProfileSignal.resonanceDays} дн. · Встреча №${activeProfileSignal.encountersCount}",
                            color = Color(0xFFC4B5FD),
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = "«${activeProfileSignal.personaObservation}»",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                openTrackInRussianPlayer(context, activeProfileSignal.artist, activeProfileSignal.title)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Text("♫ Слушать", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { EchoStateRepository.saveTrackToFinds(activeProfileSignal) },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, if (isSaved) Color(0xFF34D399) else Color(0xFF38BDF8))
                        ) {
                            Text(
                                text = if (isSaved) "✓ В находках" else "★ Сохранить",
                                color = if (isSaved) Color(0xFF34D399) else Color(0xFF38BDF8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    val symbols = listOf("🦇", "🌑", "🌊", "⚔", "🌲", "🎼", "☁")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        symbols.forEach { sym ->
                            val count = activeProfileSignal.reactions[sym] ?: 0
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B))
                                    .border(1.dp, Color(0xFF334155), CircleShape)
                                    .clickable { EchoStateRepository.sendSymbolReaction(activeProfileSignal.id, sym) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = if (count > 0) "$sym $count" else sym,
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeProfileId = null }) {
                    Text("Закрыть", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    if (showSettingsModal) {
        var artistInput by remember { mutableStateOf("") }
        var titleInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showSettingsModal = false },
            containerColor = Color(0xFF0F172A),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            title = { Text("Настройки эфира и зоны") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Ваша текущая гео-зона (размытый радиус):",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EchoStateRepository.availableZones.forEach { z ->
                            val isCur = z == myCurrentZone
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isCur) Color(0xFF0F2942) else Color(0xFF1E293B))
                                    .border(1.dp, if (isCur) Color(0xFF38BDF8) else Color(0xFF334155), RoundedCornerShape(14.dp))
                                    .clickable { EchoStateRepository.setMyZone(z) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(text = z, color = if (isCur) Color.White else Color(0xFF94A3B8), fontSize = 11.sp)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                } catch (_: Exception) {
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Доступ ОС", color = Color(0xFF38BDF8), fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val p = testPresets[presetIndex % testPresets.size]
                                presetIndex++
                                EchoStateRepository.updateMyPlayingTrack(p.second, p.third, p.first)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Тест-трек ♫", color = Color(0xFF34D399), fontSize = 11.sp)
                        }
                    }

                    OutlinedTextField(
                        value = artistInput,
                        onValueChange = { artistInput = it },
                        label = { Text("Исполнитель или IP ПК") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Композиция") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (artistInput.isNotBlank() || titleInput.isNotBlank()) {
                            EchoStateRepository.updateMyPlayingTrack(artistInput, titleInput, "manual")
                        }
                        showSettingsModal = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsModal = false }) {
                    Text("Закрыть", color = Color(0xFF94A3B8))
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
            .height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(250.dp)
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
                color = Color(0xFF38BDF8),
                radius = 6f,
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
                        radius = 20f * pulse,
                        center = Offset(sx, sy)
                    )
                }

                drawCircle(
                    color = nodeColor.copy(alpha = 0.2f),
                    radius = 12f,
                    center = Offset(sx, sy)
                )
                drawCircle(
                    color = if (isSelected) Color.White else nodeColor,
                    radius = if (isSelected) 8f else 6f,
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

        drawPath(path = path, color = color.copy(alpha = 0.42f))
        drawPath(path = path, color = color, style = Stroke(width = 2f))
    }
}
