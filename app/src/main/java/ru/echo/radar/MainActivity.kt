package ru.echo.radar

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EchoStateRepository.initLocalStore(applicationContext)
        rebindMediaListener(applicationContext)

        setContent {
            val echoColorScheme = darkColorScheme(
                primary = Color(0xFF38BDF8),
                secondary = Color(0xFFA855F7),
                background = Color(0xFF070A12),
                surface = Color(0xFF111726),
                onPrimary = Color.Black,
                onBackground = Color(0xFFE2E8F0),
                onSurface = Color(0xFFE2E8F0)
            )
            MaterialTheme(colorScheme = echoColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EchoRadarApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rebindMediaListener(applicationContext)
    }
}

fun checkNotificationPermission(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return enabledListeners.contains(context.packageName)
}

fun rebindMediaListener(context: Context) {
    if (!checkNotificationPermission(context)) return
    try {
        val componentName = ComponentName(context, EchoMediaListenerService::class.java)
        context.packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        context.packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        NotificationListenerService.requestRebind(componentName)
    } catch (_: Exception) {
    }
}

fun launchPlayerSearch(context: Context, artist: String, title: String) {
    val query = Uri.encode("$artist $title".trim())
    val yandexIntent = Intent(Intent.ACTION_VIEW, Uri.parse("yandexmusic://search?text=$query"))
    val vkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vk://search?q=$query"))
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://music.yandex.ru/search?text=$query")
    )
    try {
        context.startActivity(yandexIntent)
    } catch (_: Exception) {
        try {
            context.startActivity(vkIntent)
        } catch (_: Exception) {
            try {
                context.startActivity(webIntent)
            } catch (_: Exception) {
                Toast.makeText(context, "Не удалось открыть плеер", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun EchoRadarApp() {
    val context = LocalContext.current

    val presenceEnabled by EchoStateRepository.presenceEnabled.collectAsState()
    val myTrack by EchoStateRepository.myCurrentTrack.collectAsState()
    val nearbySignals by EchoStateRepository.nearbySignals.collectAsState()
    val nearbyCapsules by EchoStateRepository.nearbyCapsules.collectAsState()
    val listenerConnected by EchoStateRepository.listenerConnected.collectAsState()
    val savedTracks by EchoStateRepository.savedTracks.collectAsState()
    val incomingBanner by EchoStateRepository.incomingBanner.collectAsState()
    val eveningTraces by EchoStateRepository.eveningTraces.collectAsState()
    val liveZones by EchoStateRepository.liveCityZones.collectAsState()
    val myCurrentZone by EchoStateRepository.myCurrentZone.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedSignal by remember { mutableStateOf<EchoTrackSignal?>(null) }
    var selectedCapsule by remember { mutableStateOf<EchoTimeCapsule?>(null) }
    var showCapsuleCreator by remember { mutableStateOf(false) }
    var showSettingsModal by remember { mutableStateOf(false) }
    var hasNotifPermission by remember { mutableStateOf(checkNotificationPermission(context)) }

    val signalTimestamps = remember { mutableStateMapOf<String, Long>() }

    LaunchedEffect(nearbySignals) {
        val now = System.currentTimeMillis()
        nearbySignals.forEach { sig ->
            if (!signalTimestamps.containsKey(sig.id)) {
                signalTimestamps[sig.id] = now
            }
        }
        val currentIds = nearbySignals.map { it.id }.toSet()
        signalTimestamps.keys.retainAll(currentIds)
    }

    LaunchedEffect(Unit) {
        while (true) {
            hasNotifPermission = checkNotificationPermission(context)
            delay(3000)
        }
    }

    LaunchedEffect(incomingBanner) {
        if (incomingBanner != null) {
            delay(4500)
            EchoStateRepository.dismissBanner()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF050811),
                        Color(0xFF0B1325),
                        Color(0xFF060913)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Верхняя панель
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ЭХО",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 3.sp,
                            color = Color(0xFFF8FAFC)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (presenceEnabled && listenerConnected) Color(0xFF22C55E)
                                    else Color(0xFFF59E0B)
                                )
                        )
                    }
                    Text(
                        text = "Зона: $myCurrentZone",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = presenceEnabled,
                        onCheckedChange = { EchoStateRepository.togglePresence() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF38BDF8),
                            checkedTrackColor = Color(0xFF0C4A6E)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .clickable { showSettingsModal = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(text = "⚙", fontSize = 16.sp, color = Color(0xFFE2E8F0))
                    }
                }
            }

            if (!hasNotifPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1D2E)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Нет доступа к медиа-уведомлениям",
                                color = Color(0xFFFDA4AF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Нажмите, чтобы включить «ЭХО» для перехвата музыки из Яндекс.Музыки / VK",
                                color = Color(0xFFFECDD3),
                                fontSize = 11.sp
                            )
                        }
                        Text(text = "→", color = Color(0xFFFDA4AF), fontSize = 18.sp)
                    }
                }
            }

            AnimatedVisibility(
                visible = incomingBanner != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                incomingBanner?.let { banner ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF172554)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable { EchoStateRepository.dismissBanner() }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = banner.symbol, fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = banner.message,
                                color = Color(0xFFBAE6FD),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            MyPassportHeaderCard(
                myTrack = myTrack,
                presenceEnabled = presenceEnabled,
                onDropCapsuleClick = { showCapsuleCreator = true }
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val tabs = listOf("Сонар", "Карта", "Эфир (${nearbySignals.size})", "Тайники")
                tabs.forEachIndexed { index, title ->
                    val active = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Color(0xFF1E293B) else Color.Transparent)
                            .clickable { selectedTab = index }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) Color(0xFF38BDF8) else Color(0xFF64748B)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> SonarTabView(
                        myTrack = myTrack,
                        signals = nearbySignals,
                        capsules = nearbyCapsules,
                        signalTimestamps = signalTimestamps,
                        onSignalClick = { selectedSignal = it },
                        onCapsuleClick = { selectedCapsule = it }
                    )
                    1 -> CityNightMapTabView(
                        myCurrentZone = myCurrentZone,
                        signals = nearbySignals,
                        capsules = nearbyCapsules,
                        liveZones = liveZones,
                        signalTimestamps = signalTimestamps,
                        onSignalClick = { selectedSignal = it },
                        onCapsuleClick = { selectedCapsule = it }
                    )
                    2 -> StreamSignalsTabView(
                        signals = nearbySignals,
                        onSignalClick = { selectedSignal = it },
                        onReaction = { id, symbol ->
                            EchoStateRepository.sendSymbolReaction(id, symbol)
                            Toast.makeText(context, "Импульс $symbol отправлен в эфир", Toast.LENGTH_SHORT).show()
                        },
                        onSaveTrack = { signal ->
                            val added = EchoStateRepository.saveTrackToFinds(signal)
                            Toast.makeText(
                                context,
                                if (added) "Сохранено в находки" else "Уже в списке находок",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onListenClick = { artist, title -> launchPlayerSearch(context, artist, title) }
                    )
                    3 -> TracesAndCapsulesTabView(
                        myCurrentZone = myCurrentZone,
                        liveZones = liveZones,
                        capsules = nearbyCapsules,
                        savedTracks = savedTracks,
                        eveningTraces = eveningTraces,
                        onZoneSelect = { EchoStateRepository.setMyZone(it) },
                        onCapsuleClick = { selectedCapsule = it },
                        onListenClick = { artist, title -> launchPlayerSearch(context, artist, title) },
                        onRemoveSaved = { EchoStateRepository.removeSavedTrack(it) },
                        onExportPlaylist = { EchoStateRepository.exportEveningPlaylist(context) }
                    )
                }
            }
        }

        selectedSignal?.let { signal ->
            VinylPassportDialog(
                signal = signal,
                onDismiss = { selectedSignal = null },
                onReact = { symbol ->
                    EchoStateRepository.sendSymbolReaction(signal.id, symbol)
                    selectedSignal = null
                    Toast.makeText(context, "Импульс $symbol отправлен в эфир", Toast.LENGTH_SHORT).show()
                },
                onSave = {
                    val saved = EchoStateRepository.saveTrackToFinds(signal)
                    Toast.makeText(
                        context,
                        if (saved) "Трек сохранен в находки" else "Уже есть в находках",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onListen = {
                    launchPlayerSearch(context, signal.artist, signal.title)
                }
            )
        }

        selectedCapsule?.let { capsule ->
            TimeCapsuleDialog(
                capsule = capsule,
                onDismiss = { selectedCapsule = null },
                onListen = {
                    launchPlayerSearch(context, capsule.artist, capsule.title)
                }
            )
        }

        if (showCapsuleCreator) {
            DropCapsuleDialog(
                myTrack = myTrack,
                currentZone = myCurrentZone,
                onDismiss = { showCapsuleCreator = false },
                onConfirm = { secretMessage ->
                    EchoStateRepository.dropTimeCapsule(secretMessage)
                    showCapsuleCreator = false
                    Toast.makeText(context, "Музыкальный тайник спрятан в зоне $myCurrentZone", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showSettingsModal) {
            SettingsModalDialog(
                currentZone = myCurrentZone,
                availableZones = EchoStateRepository.availableZones,
                onZoneChange = { EchoStateRepository.setMyZone(it) },
                onSaveServerIp = { ip ->
                    EchoStateRepository.setCustomServerIp(ip)
                    Toast.makeText(context, "IP сервера обновлен", Toast.LENGTH_SHORT).show()
                },
                onSimulateTrack = { artist, title ->
                    EchoStateRepository.updateMyPlayingTrack(artist, title, "ru.yandex.music")
                    Toast.makeText(context, "Тестовый сигнал отправлен в эфир", Toast.LENGTH_SHORT).show()
                },
                onRebindService = {
                    rebindMediaListener(context)
                    Toast.makeText(context, "Перезапуск службы уведомлений...", Toast.LENGTH_SHORT).show()
                },
                onOpenSystemNotificationSettings = {
                    context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                },
                onDismiss = { showSettingsModal = false }
            )
        }
    }
}

@Composable
fun CityNightMapTabView(
    myCurrentZone: String,
    signals: List<EchoTrackSignal>,
    capsules: List<EchoTimeCapsule>,
    liveZones: List<CityZoneTrace>,
    signalTimestamps: Map<String, Long>,
    onSignalClick: (EchoTrackSignal) -> Unit,
    onCapsuleClick: (EchoTimeCapsule) -> Unit
) {
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF070A12))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(0.6f, 2.5f)
                        panOffset += pan
                    }
                }
                .pointerInput(signals, capsules, panOffset, zoomScale) {
                    detectTapGestures { tapOffset ->
                        val centerX = size.width / 2f + panOffset.x
                        val centerY = size.height / 2f + panOffset.y
                        val mapScale = 240f * zoomScale

                        for (sig in signals) {
                            val rad = sig.angleDegrees * (PI / 180.0)
                            val r = sig.distanceRatio.coerceIn(0.2f, 0.95f) * mapScale
                            val sx = centerX + (r * cos(rad)).toFloat()
                            val sy = centerY + (r * sin(rad)).toFloat()
                            if (hypot(tapOffset.x - sx, tapOffset.y - sy) <= 45f * zoomScale) {
                                onSignalClick(sig)
                                return@detectTapGestures
                            }
                        }

                        for (cap in capsules) {
                            val rad = cap.angleDegrees * (PI / 180.0)
                            val r = cap.distanceRatio.coerceIn(0.25f, 0.9f) * mapScale
                            val cx = centerX + (r * cos(rad)).toFloat()
                            val cy = centerY + (r * sin(rad)).toFloat()
                            if (hypot(tapOffset.x - cx, tapOffset.y - cy) <= 40f * zoomScale) {
                                onCapsuleClick(cap)
                                return@detectTapGestures
                            }
                        }
                    }
                }
        ) {
            val center = Offset(size.width / 2f + panOffset.x, size.height / 2f + panOffset.y)
            val mapScale = 240f * zoomScale

            // Координатная сетка
            val gridSize = 70f * zoomScale
            var x = (center.x % gridSize)
            while (x < size.width) {
                drawLine(Color(0xFF111827), Offset(x, 0f), Offset(x, size.height), 1f)
                x += gridSize
            }
            var y = (center.y % gridSize)
            while (y < size.height) {
                drawLine(Color(0xFF111827), Offset(0f, y), Offset(size.width, y), 1f)
                y += gridSize
            }

            // Ночная Москва-река
            val riverPath = Path().apply {
                moveTo(center.x - 380f * zoomScale, center.y + 220f * zoomScale)
                cubicTo(
                    center.x - 140f * zoomScale, center.y + 120f * zoomScale,
                    center.x - 60f * zoomScale, center.y - 80f * zoomScale,
                    center.x + 280f * zoomScale, center.y - 190f * zoomScale
                )
            }
            drawPath(
                path = riverPath,
                color = Color(0xFF0C2438),
                style = Stroke(width = 24f * zoomScale, cap = StrokeCap.Round)
            )

            // Звуковой туман кварталов (Ambient Audio Fog)
            val zoneRelativeOffsets = mapOf(
                "Вечерняя улица" to Offset(0f, -40f),
                "Сретенка · Камерные бары" to Offset(120f, -110f),
                "Парк Горького · Набережная" to Offset(-130f, 170f),
                "Покровка · Ночные кофейни" to Offset(150f, 60f),
                "Северный лесопарк" to Offset(-110f, -240f)
            )
            val zoneColors = mapOf(
                "Вечерняя улица" to Color(0xFF38BDF8),
                "Сретенка · Камерные бары" to Color(0xFFA855F7),
                "Парк Горького · Набережная" to Color(0xFF34D399),
                "Покровка · Ночные кофейни" to Color(0xFFFBBF24),
                "Северный лесопарк" to Color(0xFF818CF8)
            )

            liveZones.forEach { z ->
                val baseOff = zoneRelativeOffsets[z.placeName] ?: Offset.Zero
                val fogCenter = Offset(center.x + baseOff.x * zoomScale, center.y + baseOff.y * zoomScale)
                val fogCol = zoneColors[z.placeName] ?: Color(0xFF38BDF8)
                val fogRadius = (100f + (z.listenersCount.coerceIn(1, 10) * 12f)) * zoomScale

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            fogCol.copy(alpha = 0.12f),
                            fogCol.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        center = fogCenter,
                        radius = fogRadius
                    ),
                    radius = fogRadius,
                    center = fogCenter
                )
            }

            // Вычисление экранных позиций сигналов
            val nowMs = System.currentTimeMillis()
            val signalPositions = mutableListOf<Pair<EchoTrackSignal, Offset>>()

            signals.forEach { sig ->
                val rad = sig.angleDegrees * (PI / 180.0)
                val r = sig.distanceRatio.coerceIn(0.2f, 0.95f) * mapScale
                val pos = Offset(
                    x = center.x + (r * cos(rad)).toFloat(),
                    y = center.y + (r * sin(rad)).toFloat()
                )
                signalPositions.add(sig to pos)
            }

            // Нити резонанса между близкими по вкусу слушателями
            for (i in signalPositions.indices) {
                val (s1, p1) = signalPositions[i]
                if (s1.compatibilityPercent >= 78) {
                    // Резонанс с центральным пользователем
                    drawLine(
                        color = Color(s1.colorHex).copy(alpha = 0.22f),
                        start = center,
                        end = p1,
                        strokeWidth = 1.2f * zoomScale
                    )
                }
                for (j in i + 1 until signalPositions.size) {
                    val (s2, p2) = signalPositions[j]
                    if (s1.compatibilityPercent >= 80 && s2.compatibilityPercent >= 80) {
                        drawLine(
                            color = Color(0xFFA855F7).copy(alpha = 0.18f),
                            start = p1,
                            end = p2,
                            strokeWidth = 1f * zoomScale
                        )
                    }
                }
            }

            // Отрисовка акустических ореолов (Sound Halos)
            signalPositions.forEach { (sig, pos) ->
                val haloColor = Color(sig.colorHex)
                val isGhost = sig.moodLabel.contains("Призрачный") || sig.personaObservation.contains("Призрачный")
                val ageMs = nowMs - (signalTimestamps[sig.id] ?: nowMs)
                val decayFactor = if (isGhost) 0.35f else (1f - (ageMs / 900000f) * 0.8f).coerceIn(0.20f, 1f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            haloColor.copy(alpha = 0.35f * decayFactor),
                            haloColor.copy(alpha = 0.10f * decayFactor),
                            Color.Transparent
                        ),
                        center = pos,
                        radius = (36f + 14f * decayFactor) * zoomScale
                    ),
                    radius = (36f + 14f * decayFactor) * zoomScale,
                    center = pos
                )
                drawCircle(
                    color = haloColor.copy(alpha = decayFactor),
                    radius = (5f + 2f * decayFactor) * zoomScale,
                    center = pos
                )
                drawCircle(
                    color = Color.White.copy(alpha = decayFactor),
                    radius = 2.5f * zoomScale,
                    center = pos
                )
            }

            // Тайники
            capsules.forEach { cap ->
                val rad = cap.angleDegrees * (PI / 180.0)
                val r = cap.distanceRatio.coerceIn(0.25f, 0.9f) * mapScale
                val pos = Offset(
                    x = center.x + (r * cos(rad)).toFloat(),
                    y = center.y + (r * sin(rad)).toFloat()
                )
                val capColor = Color(0xFFF59E0B)

                drawCircle(
                    color = capColor.copy(alpha = 0.20f),
                    radius = 28f * zoomScale,
                    center = pos
                )
                val diamond = Path().apply {
                    moveTo(pos.x, pos.y - 8f * zoomScale)
                    lineTo(pos.x + 8f * zoomScale, pos.y)
                    lineTo(pos.x, pos.y + 8f * zoomScale)
                    lineTo(pos.x - 8f * zoomScale, pos.y)
                    close()
                }
                drawPath(diamond, capColor)
            }

            // Центр присутствия
            drawCircle(
                color = Color(0xFF38BDF8).copy(alpha = 0.25f),
                radius = 32f * zoomScale,
                center = center
            )
            drawCircle(
                color = Color(0xFF38BDF8),
                radius = 8f * zoomScale,
                center = center
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.9f))
                    .clickable {
                        panOffset = Offset.Zero
                        zoomScale = 1.0f
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(text = "🎯 Центр", fontSize = 11.sp, color = Color(0xFF38BDF8))
            }
        }

        Text(
            text = "Атлас города • $myCurrentZone",
            fontSize = 11.sp,
            color = Color(0xFF64748B),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        )
    }
}

@Composable
fun MyPassportHeaderCard(
    myTrack: EchoTrackSignal,
    presenceEnabled: Boolean,
    onDropCapsuleClick: () -> Unit
) {
    val auraColor = Color(myTrack.colorHex)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(auraColor.copy(alpha = 0.7f), Color(0xFF1E293B))
                ),
                shape = RoundedCornerShape(18.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GenerativeAvatarView(
                colorHex = myTrack.colorHex,
                petals = myTrack.avatarPetals,
                modifier = Modifier.size(52.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = myTrack.personaTitle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = auraColor
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• ${myTrack.moodLabel}",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (presenceEnabled) "${myTrack.artist} — ${myTrack.title}" else "Режим невидимки (эфир скрыт)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = myTrack.personaObservation,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E293B))
                    .clickable { onDropCapsuleClick() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⏳ Тайник",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8)
                )
            }
        }
    }
}

@Composable
fun SonarTabView(
    myTrack: EchoTrackSignal,
    signals: List<EchoTrackSignal>,
    capsules: List<EchoTimeCapsule>,
    signalTimestamps: Map<String, Long>,
    onSignalClick: (EchoTrackSignal) -> Unit,
    onCapsuleClick: (EchoTimeCapsule) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            LiveSonarCanvas(
                myTrack = myTrack,
                signals = signals,
                capsules = capsules,
                signalTimestamps = signalTimestamps,
                onSignalTap = onSignalClick,
                onCapsuleTap = onCapsuleClick
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Коснитесь звезды слушателя или янтарного тайника на сонаре",
            fontSize = 11.sp,
            color = Color(0xFF64748B)
        )
    }
}

@Composable
fun LiveSonarCanvas(
    myTrack: EchoTrackSignal,
    signals: List<EchoTrackSignal>,
    capsules: List<EchoTimeCapsule>,
    signalTimestamps: Map<String, Long>,
    onSignalTap: (EchoTrackSignal) -> Unit,
    onCapsuleTap: (EchoTimeCapsule) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sonar_transition")
    val radarSweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )
    val pulseFactor by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_factor"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .pointerInput(signals, capsules) {
                detectTapGestures { tapOffset ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val maxRadius = (minOf(size.width, size.height) / 2f) * 0.88f

                    for (sig in signals) {
                        val rad = sig.angleDegrees * (PI / 180.0)
                        val r = sig.distanceRatio.coerceIn(0.18f, 0.92f) * maxRadius
                        val sx = centerX + (r * cos(rad)).toFloat()
                        val sy = centerY + (r * sin(rad)).toFloat()
                        if (hypot(tapOffset.x - sx, tapOffset.y - sy) <= 46f) {
                            onSignalTap(sig)
                            return@detectTapGestures
                        }
                    }

                    for (cap in capsules) {
                        val rad = cap.angleDegrees * (PI / 180.0)
                        val r = cap.distanceRatio.coerceIn(0.22f, 0.90f) * maxRadius
                        val cx = centerX + (r * cos(rad)).toFloat()
                        val cy = centerY + (r * sin(rad)).toFloat()
                        if (hypot(tapOffset.x - cx, tapOffset.y - cy) <= 44f) {
                            onCapsuleTap(cap)
                            return@detectTapGestures
                        }
                    }
                }
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = (minOf(size.width, size.height) / 2f) * 0.88f
        val myAura = Color(myTrack.colorHex)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    myAura.copy(alpha = 0.16f),
                    Color(0xFF0F172A).copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = center,
                radius = maxRadius * 1.1f
            ),
            radius = maxRadius * 1.1f,
            center = center
        )

        listOf(0.28f, 0.55f, 0.82f, 1.0f).forEach { frac ->
            drawCircle(
                color = Color(0xFF1E293B),
                radius = maxRadius * frac,
                center = center,
                style = Stroke(width = 1.5f)
            )
        }

        drawLine(
            color = Color(0xFF1E293B).copy(alpha = 0.6f),
            start = Offset(center.x - maxRadius, center.y),
            end = Offset(center.x + maxRadius, center.y),
            strokeWidth = 1f
        )
        drawLine(
            color = Color(0xFF1E293B).copy(alpha = 0.6f),
            start = Offset(center.x, center.y - maxRadius),
            end = Offset(center.x, center.y + maxRadius),
            strokeWidth = 1f
        )

        val sweepRad = radarSweepAngle * (PI / 180.0)
        val sweepEnd = Offset(
            x = center.x + (maxRadius * cos(sweepRad)).toFloat(),
            y = center.y + (maxRadius * sin(sweepRad)).toFloat()
        )
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(myAura.copy(alpha = 0.65f), Color.Transparent),
                start = center,
                end = sweepEnd
            ),
            start = center,
            end = sweepEnd,
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        capsules.forEach { cap ->
            val rad = cap.angleDegrees * (PI / 180.0)
            val r = cap.distanceRatio.coerceIn(0.22f, 0.90f) * maxRadius
            val pos = Offset(
                x = center.x + (r * cos(rad)).toFloat(),
                y = center.y + (r * sin(rad)).toFloat()
            )
            val capColor = if (cap.isUnlocked) Color(0xFFFBBF24) else Color(0xFFF59E0B)

            drawCircle(
                color = capColor.copy(alpha = 0.22f * pulseFactor),
                radius = 22f * pulseFactor,
                center = pos
            )
            val diamond = Path().apply {
                moveTo(pos.x, pos.y - 11f)
                lineTo(pos.x + 11f, pos.y)
                lineTo(pos.x, pos.y + 11f)
                lineTo(pos.x - 11f, pos.y)
                close()
            }
            drawPath(path = diamond, color = capColor)
        }

        val nowMs = System.currentTimeMillis()
        signals.forEach { sig ->
            val rad = sig.angleDegrees * (PI / 180.0)
            val r = sig.distanceRatio.coerceIn(0.18f, 0.92f) * maxRadius
            val pos = Offset(
                x = center.x + (r * cos(rad)).toFloat(),
                y = center.y + (r * sin(rad)).toFloat()
            )
            val sigColor = Color(sig.colorHex)

            val isGhost = sig.moodLabel.contains("Призрачный") || sig.personaObservation.contains("Призрачный")
            val ageMs = nowMs - (signalTimestamps[sig.id] ?: nowMs)
            val decayFactor = if (isGhost) 0.35f else (1f - (ageMs / 900000f) * 0.8f).coerceIn(0.20f, 1f)

            if (sig.compatibilityPercent >= 75 && decayFactor > 0.4f) {
                drawLine(
                    color = sigColor.copy(alpha = 0.28f * decayFactor),
                    start = center,
                    end = pos,
                    strokeWidth = 1.5f
                )
            }

            drawCircle(
                color = sigColor.copy(alpha = 0.25f * decayFactor * pulseFactor),
                radius = (16f + 8f * decayFactor) * pulseFactor,
                center = pos
            )
            drawCircle(
                color = sigColor.copy(alpha = decayFactor),
                radius = (6f + 3f * decayFactor),
                center = pos
            )
            drawCircle(
                color = Color.White.copy(alpha = decayFactor),
                radius = 2.5f + 1f * decayFactor,
                center = pos
            )
        }

        drawCircle(
            color = myAura.copy(alpha = 0.3f),
            radius = 24f * pulseFactor,
            center = center
        )
        drawCircle(
            color = myAura,
            radius = 10f,
            center = center
        )
    }
}

@Composable
fun GenerativeAvatarView(
    colorHex: Long,
    petals: Int,
    modifier: Modifier = Modifier
) {
    val baseColor = Color(colorHex)
    val safePetals = petals.coerceIn(3, 12)
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) / 2f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(baseColor.copy(alpha = 0.45f), Color(0xFF090D16)),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )

        for (i in 0 until safePetals) {
            val angle = (i * (360.0 / safePetals)) * (PI / 180.0)
            val petalCenter = Offset(
                x = center.x + (radius * 0.42f * cos(angle)).toFloat(),
                y = center.y + (radius * 0.42f * sin(angle)).toFloat()
            )
            drawCircle(
                color = baseColor.copy(alpha = 0.55f),
                radius = radius * 0.36f,
                center = petalCenter,
                style = Stroke(width = 2f)
            )
        }

        drawCircle(
            color = baseColor,
            radius = radius * 0.22f,
            center = center
        )
        drawCircle(
            color = Color(0xFF090D16),
            radius = radius * 0.08f,
            center = center
        )
    }
}

@Composable
fun StreamSignalsTabView(
    signals: List<EchoTrackSignal>,
    onSignalClick: (EchoTrackSignal) -> Unit,
    onReaction: (String, String) -> Unit,
    onSaveTrack: (EchoTrackSignal) -> Unit,
    onListenClick: (String, String) -> Unit
) {
    if (signals.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "В радиусе сонара пока тихо...",
                color = Color(0xFF64748B),
                fontSize = 14.sp
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(signals, key = { it.id }) { sig ->
            val isGhost = sig.moodLabel.contains("Призрачный") || sig.personaObservation.contains("Призрачный")
            val aura = Color(sig.colorHex)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isGhost) aura.copy(alpha = 0.2f) else aura.copy(alpha = 0.35f),
                        RoundedCornerShape(16.dp)
                    )
                    .clickable { onSignalClick(sig) }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GenerativeAvatarView(
                            colorHex = sig.colorHex,
                            petals = sig.avatarPetals,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = sig.personaTitle,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isGhost) aura.copy(alpha = 0.7f) else aura
                                    )
                                    if (isGhost) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF1E293B))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "Призрак",
                                                fontSize = 9.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "${sig.distanceMeters} м • ${sig.compatibilityPercent}%",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Text(
                                text = "${sig.artist} — ${sig.title}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isGhost) Color(0xFF94A3B8) else Color(0xFFF8FAFC)
                            )
                            Text(
                                text = sig.personaObservation,
                                fontSize = 12.sp,
                                fontStyle = FontStyle.Italic,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("✦", "🔥", "🌊", "🌙").forEach { sym ->
                                val count = sig.reactions[sym] ?: 0
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1E293B))
                                        .clickable { onReaction(sig.id, sym) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (count > 0) "$sym $count" else sym,
                                        fontSize = 12.sp,
                                        color = Color(0xFFE2E8F0)
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E293B))
                                    .clickable { onSaveTrack(sig) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(text = "★ В находки", fontSize = 11.sp, color = Color(0xFFFACC15))
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0C4A6E))
                                    .clickable { onListenClick(sig.artist, sig.title) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(text = "▶ Слушать", fontSize = 11.sp, color = Color(0xFF38BDF8))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TracesAndCapsulesTabView(
    myCurrentZone: String,
    liveZones: List<CityZoneTrace>,
    capsules: List<EchoTimeCapsule>,
    savedTracks: List<SavedEchoTrack>,
    eveningTraces: List<String>,
    onZoneSelect: (String) -> Unit,
    onCapsuleClick: (EchoTimeCapsule) -> Unit,
    onListenClick: (String, String) -> Unit,
    onRemoveSaved: (SavedEchoTrack) -> Unit,
    onExportPlaylist: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Атмосфера городских зон",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E8F0)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                liveZones.forEach { zone ->
                    val isSelected = zone.placeName == myCurrentZone
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF1E293B) else Color(0xFF0F172A)
                        ),
                        modifier = Modifier
                            .width(210.dp)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onZoneSelect(zone.placeName) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = zone.placeName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = "Вайб: ${zone.dominantVibe} • ${zone.listenersCount} в эфире",
                                fontSize = 11.sp,
                                color = Color(0xFF38BDF8)
                            )
                            Text(
                                text = zone.recentArtists,
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Музыкальные тайники рядом (${capsules.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFBBF24)
            )
        }
        items(capsules, key = { it.id }) { cap ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1917)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .clickable { onCapsuleClick(cap) }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "⏳ ${cap.personaTitle} • ${cap.distanceMeters} м",
                            fontSize = 11.sp,
                            color = Color(0xFFFBBF24)
                        )
                        Text(
                            text = if (cap.isUnlocked) "${cap.artist} — ${cap.title}" else "Запечатанная капсула (${cap.moodLabel})",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = cap.secretMessage,
                            fontSize = 12.sp,
                            color = Color(0xFFD6D3D1),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(text = "Открыть →", fontSize = 12.sp, color = Color(0xFFFBBF24))
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Сохраненные находки (${savedTracks.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE2E8F0)
                )
                if (savedTracks.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0284C7))
                            .clickable { onExportPlaylist() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "✦ Плейлист вечера",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
        items(savedTracks) { item ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${item.artist} — ${item.title}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "${item.personaTitle} • ${item.moodLabel} • ${item.savedAt}",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "▶",
                            fontSize = 16.sp,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.clickable { onListenClick(item.artist, item.title) }
                        )
                        Text(
                            text = "✕",
                            fontSize = 16.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.clickable { onRemoveSaved(item) }
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Хроника вечерних следов",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E8F0)
            )
        }
        items(eveningTraces) { trace ->
            Text(
                text = "• $trace",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
fun VinylPassportDialog(
    signal: EchoTrackSignal,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onSave: () -> Unit,
    onListen: () -> Unit
) {
    val aura = Color(signal.colorHex)
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, aura.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GenerativeAvatarView(
                    colorHex = signal.colorHex,
                    petals = signal.avatarPetals,
                    modifier = Modifier.size(84.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = signal.personaTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = aura
                )
                Text(
                    text = "${signal.moodLabel} • ${signal.distanceMeters} м от вас • Резонанс ${signal.compatibilityPercent}%",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Пересечений в городе: ${signal.encountersCount} (дней в резонансе: ${signal.resonanceDays})",
                    fontSize = 11.sp,
                    color = Color(0xFF38BDF8)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${signal.artist} — ${signal.title}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "«${signal.personaObservation}»",
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("✦", "🔥", "🌊", "🌙").forEach { sym ->
                        OutlinedButton(onClick = { onReact(sym) }) {
                            Text(text = sym, fontSize = 16.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "★ В находки", color = Color(0xFFFACC15), fontSize = 12.sp)
                    }
                    Button(
                        onClick = onListen,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "▶ Слушать", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun TimeCapsuleDialog(
    capsule: EchoTimeCapsule,
    onDismiss: () -> Unit,
    onListen: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1917)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, Color(0xFFF59E0B), RoundedCornerShape(22.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "⏳ Музыкальный тайник", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24))
                Text(text = "Зона: ${capsule.zoneName} • ${capsule.distanceMeters} м", fontSize = 12.sp, color = Color(0xFFA8A29E))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${capsule.artist} — ${capsule.title}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = "${capsule.personaTitle} (${capsule.moodLabel})",
                    fontSize = 12.sp,
                    color = Color(0xFFFBBF24)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Послание: «${capsule.secretMessage}»",
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFFE7E5E4)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Закрыть", color = Color(0xFFD6D3D1))
                    }
                    Button(
                        onClick = onListen,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                    ) {
                        Text("▶ Открыть в плеере", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun DropCapsuleDialog(
    myTrack: EchoTrackSignal,
    currentZone: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var message by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Оставить музыкальный тайник",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Трек «${myTrack.artist} — ${myTrack.title}» будет закреплен в локации $currentZone",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Анонимное послание для нашедшего") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(if (message.isBlank()) "Слушай этот момент здесь..." else message.trim())
                        }
                    ) {
                        Text("Спрятать")
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsModalDialog(
    currentZone: String,
    availableZones: List<String>,
    onZoneChange: (String) -> Unit,
    onSaveServerIp: (String) -> Unit,
    onSimulateTrack: (String, String) -> Unit,
    onRebindService: () -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var serverIpInput by remember { mutableStateOf("") }
    var testArtist by remember { mutableStateOf("Кино") }
    var testTitle by remember { mutableStateOf("Спокойная ночь") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Настройки ЭХО",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text(text = "Локальный / облачный сервер (UDP-автопоиск активен):", fontSize = 12.sp, color = Color(0xFF94A3B8))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = serverIpInput,
                        onValueChange = { serverIpInput = it },
                        placeholder = { Text("Например: 192.168.1.45:8080") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { if (serverIpInput.isNotBlank()) onSaveServerIp(serverIpInput.trim()) }) {
                        Text("ОК")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Сменить городскую зону:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableZones.forEach { zone ->
                        val active = zone == currentZone
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0xFF0284C7) else Color(0xFF1E293B))
                                .clickable { onZoneChange(zone) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(text = zone, fontSize = 11.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Тестовый сигнал в эфир:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = testArtist,
                        onValueChange = { testArtist = it },
                        label = { Text("Артист") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = testTitle,
                        onValueChange = { testTitle = it },
                        label = { Text("Трек") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { onSimulateTrack(testArtist, testTitle) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Отправить тестовый трек")
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRebindService,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Перезапуск службы", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = onOpenSystemNotificationSettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Доступ к шторке", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Закрыть")
                }
            }
        }
    }
}
