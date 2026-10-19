package ru.echo.radar

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF06080D),
                    surface = Color(0xFF0D121E),
                    primary = Color(0xFF818CF8),
                    secondary = Color(0xFF38BDF8)
                )
            ) {
                EchoRadarAppScreen()
            }
        }
    }
}

@Composable
fun EchoRadarAppScreen() {
    val context = LocalContext.current
    val currentTrack by EchoStateRepository.currentTrack.collectAsState()
    val presenceActive by EchoStateRepository.presenceActive.collectAsState()
    val signals = remember { EchoStateRepository.nearbySignals }
    var selectedSignal by remember { mutableStateOf(signals.first()) }
    var sentSymbolMessage by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "echo_pulse")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080D))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Э Х О",
                    color = Color(0xFFF1F5F9),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 6.sp
                )
                Text(
                    text = "Музыкальное присутствие вокруг тебя",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }

            Button(
                onClick = { EchoStateRepository.togglePresence() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (presenceActive) Color(0xFF0C2D48) else Color(0xFF1E293B)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (presenceActive) "● Присутствие: Вкл" else "○ Невидимка",
                    color = if (presenceActive) Color(0xFF7DD3FC) else Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0D1322))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GenerativeAvatarCanvas(
                    phase = wavePhase,
                    seed = currentTrack.artist.length,
                    modifier = Modifier.size(46.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "В ВАШИХ НАУШНИКАХ · ${currentTrack.sourceApp}",
                        color = Color(0xFF818CF8),
                        fontSize = 10.sp
                    )
                    Text(
                        text = "♫ ${currentTrack.artist} — ${currentTrack.title}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Ваш образ ИИ: ${currentTrack.poeticPersona}",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Доступ к плеерам ОС", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                }

                OutlinedButton(
                    onClick = { showManualDialog = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Ввести трек вручную", fontSize = 11.sp, color = Color(0xFF818CF8))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF05070C))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxR = size.minDimension * 0.44f

                listOf(0.38f, 0.68f, 0.95f).forEach { factor ->
                    drawCircle(
                        color = Color(0xFF1E293B),
                        radius = (maxR * factor).coerceAtLeast(1f),
                        center = center,
                        style = Stroke(width = 1.5f)
                    )
                }

                drawCircle(
                    color = if (presenceActive) Color(0xFF38BDF8) else Color(0xFF475569),
                    radius = 7.dp.toPx(),
                    center = center
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Нажмите на звучащую песню, чтобы открыть слушателя:",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    signals.forEach { sig ->
                        val isSelected = sig.id == selectedSignal.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) Color(0xFF1E1B4B) else Color(0xCC0F172A)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF818CF8) else Color(0xFF1E293B),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    selectedSignal = sig
                                    sentSymbolMessage = ""
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "♫ ${sig.artist} — ${sig.title}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = sig.distanceLabel,
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0D121E))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GenerativeAvatarCanvas(
                    phase = wavePhase,
                    seed = selectedSignal.artist.length + 3,
                    modifier = Modifier.size(54.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "МУЗЫКАЛЬНОЕ ИМЯ (ИИ-КУРАТОР)",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp
                    )
                    Text(
                        text = selectedSignal.poeticName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Совместимость вкуса: ${selectedSignal.compatibility}% · ${selectedSignal.sourceApp}",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp
                    )
                }
            }

            if (selectedSignal.resonanceDays != null) {
                Text(
                    text = "✦ ${selectedSignal.resonanceDays}: совпала ваша музыкальная фаза жизни",
                    color = Color(0xFFC7D2FE),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E1B4B))
                        .padding(8.dp)
                )
            }

            Text(
                text = "«${selectedSignal.aiObservation}»",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic
            )

            Text(
                text = "Отправить музыкальное настроение (без лайков и оценок):",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("🦇", "🌑", "🌊", "⚔", "🌲", "🎼", "☁").forEach { symbol ->
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF161F33))
                            .clickable {
                                sentSymbolMessage = "Тихий знак $symbol отправлен для «${selectedSignal.poeticName}»"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = symbol, fontSize = 16.sp)
                    }
                }
            }

            if (sentSymbolMessage.isNotEmpty()) {
                Text(
                    text = sentSymbolMessage,
                    color = Color(0xFF818CF8),
                    fontSize = 11.sp
                )
            }
        }
    }

    if (showManualDialog) {
        ManualTrackDialog(
            onDismiss = { showManualDialog = false },
            onSubmit = { artist, title ->
                EchoStateRepository.updateDetectedMusic(artist, title, "Ручной режим")
                showManualDialog = false
            }
        )
    }
}

@Composable
fun GenerativeAvatarCanvas(phase: Float, seed: Int, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF05070C))
            .border(1.dp, Color(0xFF334155), CircleShape)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = (size.minDimension * 0.28f).coerceAtLeast(2f)
        val petals = 4 + (seed % 5)

        val path = Path()
        val steps = 48
        for (i in 0..steps) {
            val theta = (i.toFloat() / steps) * 6.28318f
            val wave = sin(theta * petals + phase) * (baseR * 0.25f)
            val r = (baseR + wave).coerceAtLeast(1f)
            val x = cx + cos(theta) * r
            val y = cy + sin(theta) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        drawPath(
            path = path,
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF818CF8), Color(0xFF38BDF8), Color.Transparent)
            )
        )
        drawPath(
            path = path,
            color = Color(0xFFA5B4FC),
            style = Stroke(width = 2f)
        )
    }
}

@Composable
fun ManualTrackDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var artist by remember { mutableStateOf("Bohren & der Club of Gore") }
    var title by remember { mutableStateOf("Prowler") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        title = {
            Text("Поделиться треком вручную", color = Color.White, fontSize = 16.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Исполнитель") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название трека") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(artist, title) }) {
                Text("Транслировать в ЭХО", color = Color(0xFF818CF8))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = Color(0xFF94A3B8))
            }
        }
    )
}
