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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.scale
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
            EchoMainScreen()
        }
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return enabledListeners.contains(context.packageName)
}

private fun rebindMediaListener(context: Context) {
    try {
        NotificationListenerService.requestRebind(
            ComponentName(context, EchoMediaListenerService::class.java)
        )
    } catch (_: Exception) {
    }
}

private fun launchPlayerSearch(context: Context, artist: String, title: String) {
    val fullTitle = "$artist — $title".trim()
    val queryEncoded = Uri.encode("$artist $title")

    try {
        val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, "$artist $title")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (mediaIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mediaIntent)
            Toast.makeText(context, "Запуск в плеере: $fullTitle", Toast.LENGTH_SHORT).show()
            return
        }
    } catch (_: Exception) {
    }

    try {
        val searchUri = Uri.parse("https://music.yandex.ru/search?text=$queryEncoded")
        val browserIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
        Toast.makeText(context, "Открываем поиск: $fullTitle", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "Не удалось открыть музыкальное приложение", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EchoMainScreen() {
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

    var hasOsAccess by remember { mutableStateOf(checkNotificationPermission(context)) }
    var currentTab by remember { mutableIntStateOf(0) }
    var selectedSignalId by remember { mutableStateOf<String?>(null) }
    var isSettingsOpen by remember { mutableStateOf(false) }

    val activeSignal = nearbySignals.find { it.id == selectedSignalId }
    val isPcLinked = myTrack.sourceName.contains("Эфир ПК")

    // Автоматическая подстановка вашего IP при первом старте, если еще не сохранен
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("echo_radar_local_store", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("saved_custom_host", null)
        if (savedIp.isNullOrBlank()) {
            EchoStateRepository.setCustomServerIp("192.168.0.214:8080")
        }
        while (true) {
            val granted = checkNotificationPermission(context)
            hasOsAccess = granted
            if (granted) rebindMediaListener(context)
            delay(2500)
        }
    }

    LaunchedEffect(incomingBanner?.id) {
        if (incomingBanner != null) {
            delay(6000)
            EchoStateRepository.dismissBanner()
        }
    }

    val demoTracks = remember {
        listOf(
            Triple("Яндекс Музыка", "Кино", "Спокойная ночь"),
            Triple("VK Музыка", "Wardruna", "Helvegen"),
            Triple("Poweramp", "Burial", "Archangel"),
            Triple("Звук", "Radiohead", "Weird Fishes")
        )
    }
    var demoIndex by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF060A12)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // ВЕРХНЯЯ ПАНЕЛЬ
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Э  Х  О",
                        color = Color(0xFFF8FAFC),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "📍 $myCurrentZone",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { isSettingsOpen = true }
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
                                if (hasOsAccess || listenerConnected) Color(0xFF1E293B) else Color(0xFFF59E0B),
                                CircleShape
                            )
                            .clickable { isSettingsOpen = true }
                            .padding(horizontal = 11.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "⚙",
                            color = if (hasOsAccess || listenerConnected) Color(0xFF94A3B8) else Color(0xFFF59E0B),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // ВСПЛЫВАЮЩИЙ БАННЕР ОТКЛИКА
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

            // КАРТОЧКА ВАШЕЙ ТЕКУЩЕЙ ВОЛНЫ (С ОТОБРАЖЕНИЕМ СТАТУСА «Эфир ПК ●»)
            val myAuraColor = Color(myTrack.colorHex)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(myAuraColor.copy(alpha = 0.16f), Color(0xFF0F172A))
                        )
                    )
                    .border(1.dp, myAuraColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .clickable { isSettingsOpen = true }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GenerativeAvatarView(
                    color = myAuraColor,
                    petals = myTrack.avatarPetals,
                    modifier = Modifier.size(46.dp)
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
                    // Строка статуса подключения к ПК
                    Text(
                        text = myTrack.sourceName,
                        color = if (isPcLinked) Color(0xFF34D399) else Color(0xFF64748B),
                        fontSize = 11.sp,
                        fontWeight = if (isPcLinked) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (myTrack.reactions.isNotEmpty()) {
                        val reactLine = myTrack.reactions.entries.joinToString("  ") { "${it.key} ${it.value}" }
                        Text(
                            text = "Отклики: $reactLine",
                            color = Color(0xFFA78BFA),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ПЕРЕКЛЮЧАТЕЛЬ ВКЛАДОК
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val savedCount = if (savedTracks.isNotEmpty()) " (${savedTracks.size})" else ""
                val tabNames = listOf("◎ Сонар", "≋ Поток", "✦ Следы$savedCount")
                tabNames.forEachIndexed { idx, label ->
                    val isSelected = currentTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isSelected) Color(0xFF1E293B) else Color.Transparent)
                            .clickable { currentTab = idx }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // СОДЕРЖИМОЕ ВКЛАДОК
            when (currentTab) {
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
                        LiveSonarCanvas(
                            signals = nearbySignals,
                            selectedSignalId = selectedSignalId ?: "",
                            myColor = myAuraColor,
                            onSelectSignal = { selectedSignalId = it.id }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Нажмите на сигнал на сонаре или в списке, чтобы открыть паспорт волны:",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            nearbySignals.forEach { sig ->
                                val sigColor = Color(sig.colorHex)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(sigColor.copy(alpha = 0.12f), Color(0xFF111827))
                                            )
                                        )
                                        .border(1.dp, sigColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                        .clickable { selectedSignalId = sig.id }
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
                                                .background(sigColor)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "♫ ${sig.artist} — ${sig.title}",
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${sig.personaTitle} · ${sig.compatibilityPercent}% рез.",
                                                color = Color(0xFF94A3B8),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Text(
                                        text = "~${sig.distanceMeters} м",
                                        color = sigColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
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
                            val sigColor = Color(sig.colorHex)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(sigColor.copy(alpha = 0.15f), Color(0xFF111827))
                                        )
                                    )
                                    .border(1.dp, sigColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
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
                                        text = "${sig.personaTitle} · ${sig.moodLabel}",
                                        color = sigColor,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Совместимость ${sig.compatibilityPercent}% · Встреча №${sig.encountersCount}",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp
                                    )
                                }
                                Text(
                                    text = "~${sig.distanceMeters} м",
                                    color = sigColor,
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
                                            color = Color(0xFFF1F5F9),
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
                                                .clickable { launchPlayerSearch(context, item.artist, item.title) }
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
                                    Text(
                                        text = zone.placeName,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = zone.dominantVibe,
                                        color = Color(0xFF34D399),
                                        fontSize = 11.sp
                                    )
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

    // МОДАЛЬНОЕ ОКНО: ВИНИЛОВАЯ КАРТОЧКА-ПАСПОРТ + АНИМАЦИЯ ИСКРЫ ОТКЛИКА
    if (activeSignal != null) {
        val isTrackSaved = savedTracks.any {
            it.artist.equals(activeSignal.artist, ignoreCase = true) &&
                it.title.equals(activeSignal.title, ignoreCase = true)
        }
        val auraColor = Color(activeSignal.colorHex)
        var lastClickedSymbol by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(lastClickedSymbol) {
            if (lastClickedSymbol != null) {
                delay(500)
                lastClickedSymbol = null
            }
        }

        val glowTransition = rememberInfiniteTransition(label = "aura_glow")
        val auraAlpha by glowTransition.animateFloat(
            initialValue = 0.20f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "aura_alpha"
        )

        AlertDialog(
            onDismissRequest = { selectedSignalId = null },
            containerColor = Color(0xFF080D1A),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            shape = RoundedCornerShape(22.dp),
            title = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(auraColor.copy(alpha = auraAlpha), Color(0xFF111827))
                            )
                        )
                        .border(1.dp, auraColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GenerativeAvatarView(
                            color = auraColor,
                            petals = activeSignal.avatarPetals,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = activeSignal.personaTitle,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Резонанс вкусов: ${activeSignal.compatibilityPercent}% · ~${activeSignal.distanceMeters} м",
                                color = auraColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF111827))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "♫ ${activeSignal.artist} — ${activeSignal.title}",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Атмосфера: ${activeSignal.moodLabel} (${activeSignal.sourceName})",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF131B2E))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "✦ Дней в резонансе: ${activeSignal.resonanceDays} · Встреча №${activeSignal.encountersCount}",
                            color = Color(0xFFC4B5FD),
                            fontSize = 11.sp
                        )
                    }

                    Text(
                        text = "«${activeSignal.personaObservation}»",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                launchPlayerSearch(context, activeSignal.artist, activeSignal.title)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Text("♫ Слушать", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { EchoStateRepository.saveTrackToFinds(activeSignal) },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, if (isTrackSaved) Color(0xFF34D399) else Color(0xFF38BDF8))
                        ) {
                            Text(
                                text = if (isTrackSaved) "✓ В находках" else "★ Сохранить",
                                color = if (isTrackSaved) Color(0xFF34D399) else Color(0xFF38BDF8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Отправить импульс в эфир:",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                        if (lastClickedSymbol != null) {
                            Text(
                                text = "✦ Импульс $lastClickedSymbol отправлен!",
                                color = auraColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    val reactionSymbols = listOf("🦇", "🌑", "🌊", "⚔", "🌲", "🎼", "☁")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        reactionSymbols.forEach { sym ->
                            val count = activeSignal.reactions[sym] ?: 0
                            val isTriggered = lastClickedSymbol == sym
                            val scaleVal by animateFloatAsState(
                                targetValue = if (isTriggered) 1.25f else 1.0f,
                                animationSpec = tween(durationMillis = 220),
                                label = "sym_scale"
                            )

                            Box(
                                modifier = Modifier
                                    .scale(scaleVal)
                                    .clip(CircleShape)
                                    .background(if (isTriggered) auraColor.copy(alpha = 0.35f) else Color(0xFF111827))
                                    .border(
                                        1.dp,
                                        if (isTriggered) Color.White else auraColor.copy(alpha = 0.45f),
                                        CircleShape
                                    )
                                    .clickable {
                                        lastClickedSymbol = sym
                                        EchoStateRepository.sendSymbolReaction(activeSignal.id, sym)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (count > 0) "$sym $count" else sym,
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedSignalId = null }) {
                    Text("Закрыть", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    // МОДАЛЬНОЕ ОКНО НАСТРОЕК С ЗАПОМИНАНИЕМ IP
    if (isSettingsOpen) {
        val prefs = remember {
            context.getSharedPreferences("echo_radar_local_store", Context.MODE_PRIVATE)
        }
        val currentSavedHost = remember {
            prefs.getString("saved_custom_host", "192.168.0.214:8080") ?: "192.168.0.214:8080"
        }
        var ipInput by remember { mutableStateOf(currentSavedHost) }

        AlertDialog(
            onDismissRequest = { isSettingsOpen = false },
            containerColor = Color(0xFF0F172A),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            title = { Text("Настройки эфира и зоны") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Ваша текущая гео-зона:",
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
                                    .border(
                                        1.dp,
                                        if (isCur) Color(0xFF38BDF8) else Color(0xFF334155),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { EchoStateRepository.setMyZone(z) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = z,
                                    color = if (isCur) Color.White else Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
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
                                val item = demoTracks[demoIndex % demoTracks.size]
                                demoIndex++
                                EchoStateRepository.updateMyPlayingTrack(item.second, item.third, item.first)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Тест-трек ♫", color = Color(0xFF34D399), fontSize = 11.sp)
                        }
                    }

                    Text(
                        text = if (isPcLinked) "● Связь с сервером ПК активна ($currentSavedHost)"
                        else "○ Поиск сервера ПК ($currentSavedHost)...",
                        color = if (isPcLinked) Color(0xFF34D399) else Color(0xFFF59E0B),
                        fontSize = 11.sp
                    )

                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { newValue ->
                            // Защита от случайной вставки огромного куска текста из буфера обмена
                            if (newValue.length <= 45) {
                                ipInput = newValue
                            }
                        },
                        label = { Text("IP-адрес ПК (напр. 192.168.0.214:8080)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanIp = ipInput.trim()
                        if (cleanIp.isNotEmpty() && cleanIp.length <= 45) {
                            EchoStateRepository.setCustomServerIp(cleanIp)
                            Toast.makeText(context, "IP сервера сохранён: $cleanIp", Toast.LENGTH_SHORT).show()
                        }
                        isSettingsOpen = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("Сохранить IP")
                }
            },
            dismissButton = {
                TextButton(onClick = { isSettingsOpen = false }) {
                    Text("Закрыть", color = Color(0xFF94A3B8))
                }
            }
        )
    }
}

@Composable
fun LiveSonarCanvas(
    signals: List<EchoTrackSignal>,
    selectedSignalId: String,
    myColor: Color,
    onSelectSignal: (EchoTrackSignal) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sonar_anim")
    val radarAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_angle"
    )
    val sonarWave by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sonar_wave"
    )
    val nodePulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.32f,
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
                    detectTapGestures { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val maxR = (size.width.coerceAtMost(size.height) / 2f) * 0.88f

                        val closest = signals.minByOrNull { sig ->
                            val rad = sig.angleDegrees * (PI.toFloat() / 180f)
                            val r = maxR * sig.distanceRatio
                            val sx = cx + r * cos(rad)
                            val sy = cy + r * sin(rad)
                            hypot(offset.x - sx, offset.y - sy)
                        }
                        if (closest != null) {
                            onSelectSignal(closest)
                        }
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = (size.minDimension / 2f) * 0.88f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0A192F), Color(0xFF04070D)),
                    center = Offset(cx, cy),
                    radius = maxR
                ),
                radius = maxR,
                center = Offset(cx, cy)
            )

            // Расходящаяся звуковая волна эхолота
            drawCircle(
                color = myColor.copy(alpha = (1f - sonarWave) * 0.35f),
                radius = maxR * sonarWave,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )

            listOf(0.33f, 0.66f, 1.0f).forEach { ring ->
                drawCircle(
                    color = Color(0xFF1E293B).copy(alpha = 0.75f),
                    radius = maxR * ring,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f)
                )
            }

            val radAngle = radarAngle * (PI.toFloat() / 180f)
            val beamTarget = Offset(
                cx + maxR * cos(radAngle),
                cy + maxR * sin(radAngle)
            )
            drawLine(
                color = Color(0xFF38BDF8).copy(alpha = 0.55f),
                start = Offset(cx, cy),
                end = beamTarget,
                strokeWidth = 3f
            )

            drawCircle(
                color = myColor,
                radius = 6.5f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = myColor.copy(alpha = 0.35f),
                radius = 14f * nodePulse,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2f)
            )

            signals.forEach { sig ->
                val rad = sig.angleDegrees * (PI.toFloat() / 180f)
                val r = maxR * sig.distanceRatio
                val sx = cx + r * cos(rad)
                val sy = cy + r * sin(rad)
                val isSelected = sig.id == selectedSignalId
                val sigColor = Color(sig.colorHex)
                val depthAlpha = (1.1f - sig.distanceRatio * 0.55f).coerceIn(0.38f, 1.0f)

                if (isSelected) {
                    drawLine(
                        color = sigColor.copy(alpha = 0.75f),
                        start = Offset(cx, cy),
                        end = Offset(sx, sy),
                        strokeWidth = 2f
                    )
                    drawCircle(
                        color = sigColor.copy(alpha = 0.35f),
                        radius = 22f * nodePulse,
                        center = Offset(sx, sy)
                    )
                }

                drawCircle(
                    color = sigColor.copy(alpha = 0.25f * depthAlpha),
                    radius = 12f,
                    center = Offset(sx, sy)
                )
                drawCircle(
                    color = if (isSelected) Color.White else sigColor.copy(alpha = depthAlpha),
                    radius = if (isSelected) 8f else 5.5f,
                    center = Offset(sx, sy)
                )
            }
        }
    }
}

@Composable
fun GenerativeAvatarView(
    color: Color,
    petals: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase_shift"
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
