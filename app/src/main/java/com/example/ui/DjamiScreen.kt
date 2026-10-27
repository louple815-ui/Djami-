package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.theme.DjamiBackground
import com.example.ui.theme.DjamiBorder
import com.example.ui.theme.DjamiGold
import com.example.ui.theme.DjamiGoldBorder
import com.example.ui.theme.DjamiGoldBright
import com.example.ui.theme.DjamiGoldContainer
import com.example.ui.theme.DjamiGoldDark
import com.example.ui.theme.DjamiGreen
import com.example.ui.theme.DjamiGreenDark
import com.example.ui.theme.DjamiGreenLight
import com.example.ui.theme.DjamiStatusError
import com.example.ui.theme.DjamiStatusListening
import com.example.ui.theme.DjamiStatusProcessing
import com.example.ui.theme.DjamiStatusReady
import com.example.ui.theme.DjamiStatusSpeaking
import com.example.ui.theme.DjamiSurface
import com.example.ui.theme.DjamiSurfaceVariant
import com.example.ui.theme.DjamiTextMuted
import com.example.ui.theme.DjamiTextPrimary
import com.example.ui.theme.DjamiTextSecondary
import com.example.ui.theme.DjamiWhite

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DjamiScreen(
    viewModel: DjamiViewModel,
    uiState: DjamiUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    // L'action à reprendre après la demande de permission est mémorisée :
    // démarrer l'écoute manuelle ou activer la veille sont deux parcours distincts.
    var pendingPermissionAction by remember { mutableStateOf("listen") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!recordAudioGranted) {
            viewModel.logDiagnostic("Permission microphone refusée. Autorisez le microphone dans les paramètres Android.")
            return@rememberLauncherForActivityResult
        }

        when (pendingPermissionAction) {
            "wake_on" -> viewModel.toggleWakeWord(true)
            "wake_off" -> viewModel.toggleWakeWord(false)
            else -> viewModel.startListening()
        }
    }

    val requestPermissionsAndSpeak = {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            viewModel.startListening()
        } else {
            pendingPermissionAction = "listen"
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    val requestPermissionsAndToggleWake = { shouldActivate: Boolean ->
        // L'arrêt de la veille ne doit jamais être bloqué par une permission refusée.
        if (!shouldActivate) {
            viewModel.toggleWakeWord(false)
        } else {
            val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val notGranted = permissionsToRequest.filter {
                ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            if (notGranted.isEmpty()) {
                viewModel.toggleWakeWord(true)
            } else {
                pendingPermissionAction = "wake_on"
                permissionLauncher.launch(notGranted.toTypedArray())
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DjamiBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.5.dp, DjamiGold, RoundedCornerShape(10.dp))
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.djami_logo),
                                contentDescription = "Logo DJAMI",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "DJAMI",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp,
                                    letterSpacing = 1.sp,
                                    color = DjamiGoldDark
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "AI",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = DjamiGreenDark,
                                    modifier = Modifier
                                        .background(DjamiGreenLight, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "Assistant Vocal & IA en Français",
                                fontSize = 11.sp,
                                color = DjamiTextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Paramètres Voix",
                            tint = DjamiGoldDark
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DjamiWhite)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {

            // 1. Badge d'état visuel (Prêt, Écoute, Traitement, Réponse, Arrêté, Erreur)
            item {
                StatusIndicatorBadge(
                    status = uiState.status,
                    statusMessage = uiState.statusMessage,
                    isSpeaking = uiState.isSpeaking,
                    canRetry = uiState.canRetryListening,
                    onRetry = { viewModel.retryListening() }
                )
            }

            // 2. Avertissement voix française / configuration
            if (!uiState.isFrenchVoiceAvailable) {
                item {
                    FrenchVoiceWarningCard(onOpenSettings = { viewModel.openTtsSettings() })
                }
            }

            // 3. Zone de transcription reconnue
            item {
                TranscriptionCard(
                    text = uiState.recognizedTranscription,
                    isListening = uiState.status == DjamiStatusType.LISTENING
                )
            }

            // 4. Zone de réponse de DJAMI
            item {
                ResponseCard(
                    text = uiState.assistantResponse,
                    isSpeaking = uiState.isSpeaking,
                    onRepeat = { viewModel.repeatLastResponse() },
                    onStop = { viewModel.stopSpeech() }
                )
            }

            // 5. Bouton principal "Parler à DJAMI"
            item {
                MainSpeakButton(
                    isListening = uiState.status == DjamiStatusType.LISTENING,
                    isSpeaking = uiState.isSpeaking,
                    onClick = {
                        when {
                            uiState.status == DjamiStatusType.LISTENING -> viewModel.stopListening()
                            uiState.isSpeaking -> viewModel.stopSpeech()
                            else -> requestPermissionsAndSpeak()
                        }
                    }
                )
            }

            // 6. Barre d'options : Mode Hors-Ligne & Veille Vocale "Hey DJAMI"
            item {
                ControlsRow(
                    isOfflineMode = uiState.isOfflineMode,
                    onToggleOffline = { viewModel.toggleOfflineMode(it) },
                    isWakeActive = uiState.isWakeWordActive,
                    onToggleWake = { requestPermissionsAndToggleWake(it) }
                )
            }

            // 7. Commandes locales rapides (Chips interactifs)
            item {
                Text(
                    text = "Commandes locales rapides",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = DjamiTextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    QuickCommandChip(label = "🕒 Quelle heure est-il ?", onClick = { viewModel.processUserInput("quelle heure est-il") })
                    QuickCommandChip(label = "📅 Quelle est la date ?", onClick = { viewModel.processUserInput("quelle est la date") })
                    QuickCommandChip(label = "💬 Ouvre WhatsApp", onClick = { viewModel.processUserInput("ouvre whatsapp") })
                    QuickCommandChip(label = "▶️ Ouvre YouTube", onClick = { viewModel.processUserInput("ouvre youtube") })
                    QuickCommandChip(label = "⚙️ Paramètres", onClick = { viewModel.processUserInput("ouvre les paramètres") })
                    QuickCommandChip(label = "📷 Appareil photo", onClick = { viewModel.processUserInput("ouvre l'appareil photo") })
                    QuickCommandChip(label = "🖼️ Galerie", onClick = { viewModel.processUserInput("ouvre la galerie") })
                    QuickCommandChip(label = "🌐 Navigateur", onClick = { viewModel.processUserInput("ouvre mon navigateur") })
                    QuickCommandChip(label = "😂 Raconte une blague", onClick = { viewModel.processUserInput("raconte une blague") })
                    QuickCommandChip(label = "✨ Citation", onClick = { viewModel.processUserInput("donne-moi une citation") })
                }
            }

            // 8. Champ d'écriture et correction manuelle
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DjamiSurface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DjamiGoldBorder, DjamiBorder))),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Saisie écrite / Test direct",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = DjamiTextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = { Text("Écrire à DJAMI…", fontSize = 14.sp, color = DjamiTextMuted) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("text_input_field"),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = DjamiGold,
                                    unfocusedBorderColor = DjamiBorder,
                                    focusedContainerColor = DjamiBackground,
                                    unfocusedContainerColor = DjamiBackground
                                ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    if (inputText.isNotBlank()) {
                                        viewModel.processUserInput(inputText)
                                        inputText = ""
                                    }
                                })
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (inputText.isNotBlank()) {
                                        viewModel.processUserInput(inputText)
                                        inputText = ""
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DjamiGoldDark),
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("send_button"),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Envoyer",
                                    tint = DjamiWhite
                                )
                            }
                        }
                    }
                }
            }

            // 9. Tiroir diagnostic repliable
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DjamiSurfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDiagnostics = !showDiagnostics }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = DjamiGreenDark,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Journal de diagnostic en temps réel",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DjamiTextPrimary
                                )
                            }
                            Text(
                                text = if (showDiagnostics) "Masquer ▲" else "Afficher ▼",
                                fontSize = 12.sp,
                                color = DjamiGoldDark,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        AnimatedVisibility(visible = showDiagnostics) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                if (uiState.diagnosticLogs.isEmpty()) {
                                    Text("Aucun événement pour le moment.", fontSize = 12.sp, color = DjamiTextMuted)
                                } else {
                                    uiState.diagnosticLogs.forEach { log ->
                                        Text(
                                            text = log,
                                            fontSize = 11.sp,
                                            color = DjamiTextSecondary,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal de réglage de la voix
    if (showSettingsDialog) {
        VoiceSettingsDialog(
            speechRate = uiState.speechRate,
            speechPitch = uiState.speechPitch,
            hasFemaleVoice = uiState.hasFemaleVoice,
            onRateChange = { viewModel.setSpeechRate(it) },
            onPitchChange = { viewModel.setSpeechPitch(it) },
            onOpenSystemSettings = { viewModel.openTtsSettings() },
            onTestVoice = { viewModel.ttsManager.speak("Bonjour, je suis la voix française de DJAMI. Votre assistante est parfaitement configurée.") },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun StatusIndicatorBadge(
    status: DjamiStatusType,
    statusMessage: String,
    isSpeaking: Boolean,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {}
) {
    val (bgColor, borderColor, textColor, icon) = when (status) {
        DjamiStatusType.READY -> Quadruple(
            DjamiGreenLight,
            DjamiGreen,
            DjamiGreenDark,
            Icons.Default.CheckCircle
        )
        DjamiStatusType.LISTENING -> Quadruple(
            Color(0xFFFEF3C7),
            DjamiStatusListening,
            Color(0xFF92400E),
            Icons.Default.Hearing
        )
        DjamiStatusType.PROCESSING -> Quadruple(
            Color(0xFFDBEAFE),
            DjamiStatusProcessing,
            Color(0xFF1E40AF),
            Icons.Default.GraphicEq
        )
        DjamiStatusType.SPEAKING -> Quadruple(
            DjamiGoldContainer,
            DjamiGoldBright,
            DjamiGoldDark,
            Icons.AutoMirrored.Filled.VolumeUp
        )
        DjamiStatusType.STOPPED -> Quadruple(
            Color(0xFFF3F4F6),
            DjamiBorder,
            DjamiTextSecondary,
            Icons.Default.Stop
        )
        DjamiStatusType.ERROR -> Quadruple(
            Color(0xFFFEE2E2),
            DjamiStatusError,
            Color(0xFF991B1B),
            Icons.Default.Warning
        )
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_badge")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = when (status) {
                            DjamiStatusType.READY -> "ÉTAT : PRÊT"
                            DjamiStatusType.LISTENING -> "ÉTAT : EN ÉCOUTE"
                            DjamiStatusType.PROCESSING -> "ÉTAT : TRAITEMENT"
                            DjamiStatusType.SPEAKING -> "ÉTAT : VOCALISATION"
                            DjamiStatusType.STOPPED -> "ÉTAT : ARRÊTÉ"
                            DjamiStatusType.ERROR -> "ÉTAT : ERREUR"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = statusMessage,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = DjamiTextPrimary
                    )
                }
            }

            if (status == DjamiStatusType.ERROR && canRetry) {
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
                    border = androidx.compose.foundation.BorderStroke(1.dp, textColor),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Réessayer", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TranscriptionCard(text: String, isListening: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DjamiSurface),
        shape = RoundedCornerShape(18.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DjamiBorder, DjamiGoldBorder))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (isListening) DjamiStatusListening else DjamiTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Paroles reconnues",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = DjamiTextSecondary
                    )
                }
                if (isListening) {
                    Text(
                        text = "En direct…",
                        fontSize = 11.sp,
                        color = DjamiStatusListening,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (text.isNotBlank()) text else "Appuyez sur « Parler à DJAMI » pour dicter un message ou une commande…",
                fontSize = 16.sp,
                fontWeight = if (text.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                color = if (text.isNotBlank()) DjamiTextPrimary else DjamiTextMuted,
                lineHeight = 22.sp,
                modifier = Modifier.testTag("transcription_text")
            )
        }
    }
}

@Composable
fun ResponseCard(
    text: String,
    isSpeaking: Boolean,
    onRepeat: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DjamiSurface),
        shape = RoundedCornerShape(18.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DjamiGoldBright, DjamiGoldDark))),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isSpeaking) DjamiGoldBright else DjamiGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Réponse de DJAMI",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = DjamiGoldDark,
                        letterSpacing = 0.5.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isSpeaking) {
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("stop_voice_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Arrêter la voix",
                                tint = DjamiStatusError
                            )
                        }
                    }
                    IconButton(
                        onClick = onRepeat,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("repeat_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Répéter la réponse",
                            tint = DjamiGoldDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                color = DjamiTextPrimary,
                lineHeight = 24.sp,
                modifier = Modifier.testTag("response_text")
            )
        }
    }
}

@Composable
fun MainSpeakButton(
    isListening: Boolean,
    isSpeaking: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.06f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val gradient = when {
        isListening -> Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFD97706), Color(0xFFB45309)))
        isSpeaking -> Brush.linearGradient(listOf(Color(0xFFDC2626), Color(0xFFB91C1C), Color(0xFF991B1B)))
        else -> Brush.linearGradient(listOf(DjamiGoldBright, DjamiGold, DjamiGoldDark))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(30.dp),
            shadowElevation = if (isListening || isSpeaking) 12.dp else 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .scale(scale)
                .testTag("main_speak_button")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradient),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isListening || isSpeaking) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        tint = DjamiWhite,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when {
                            isListening -> "Terminer l'écoute"
                            isSpeaking -> "Arrêter la voix"
                            else -> "🎤 Parler à DJAMI"
                        },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = DjamiWhite
                    )
                }
            }
        }
    }
}

@Composable
fun ControlsRow(
    isOfflineMode: Boolean,
    onToggleOffline: (Boolean) -> Unit,
    isWakeActive: Boolean,
    onToggleWake: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Toggle Hors-ligne
        Card(
            colors = CardDefaults.cardColors(containerColor = DjamiSurface),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DjamiBorder, DjamiGoldBorder))),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (isOfflineMode) Icons.Default.CloudOff else Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (isOfflineMode) DjamiGreenDark else DjamiGoldDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Switch(
                        checked = !isOfflineMode,
                        onCheckedChange = { onToggleOffline(!it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DjamiWhite,
                            checkedTrackColor = DjamiGoldDark,
                            uncheckedThumbColor = DjamiWhite,
                            uncheckedTrackColor = DjamiGreen
                        ),
                        modifier = Modifier.testTag("online_mode_switch")
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isOfflineMode) "Mode Hors-ligne" else "Mode En ligne (IA)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = DjamiTextPrimary
                )
                Text(
                    text = if (isOfflineMode) "Commandes locales" else "IA générative Gemini",
                    fontSize = 10.sp,
                    color = DjamiTextSecondary
                )
            }
        }

        // Toggle Veille vocale Hey DJAMI
        Card(
            colors = CardDefaults.cardColors(containerColor = DjamiSurface),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DjamiBorder, DjamiGoldBorder))),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = null,
                        tint = if (isWakeActive) DjamiGoldDark else DjamiTextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Switch(
                        checked = isWakeActive,
                        onCheckedChange = onToggleWake,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DjamiWhite,
                            checkedTrackColor = DjamiGoldDark
                        ),
                        modifier = Modifier.testTag("wake_word_switch")
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Veille « Hey DJAMI »",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = DjamiTextPrimary
                )
                Text(
                    text = if (isWakeActive) "Écoute en arrière-plan" else "Désactivée",
                    fontSize = 10.sp,
                    color = if (isWakeActive) DjamiGreenDark else DjamiTextMuted
                )
            }
        }
    }
}

@Composable
fun QuickCommandChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = DjamiSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjamiBorder),
        shadowElevation = 1.dp
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = DjamiTextPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun FrenchVoiceWarningCard(onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFB45309),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Données vocales françaises manquantes",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFF92400E)
                )
                Text(
                    text = "Pour une voix fluide, téléchargez le pack français dans les paramètres Android.",
                    fontSize = 11.sp,
                    color = Color(0xFFB45309)
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text("Régler", color = Color(0xFF92400E), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun VoiceSettingsDialog(
    speechRate: Float,
    speechPitch: Float,
    hasFemaleVoice: Boolean,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onTestVoice: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = DjamiGoldDark)
            ) {
                Text("Fermer")
            }
        },
        title = {
            Text(
                text = "Paramètres de la Voix",
                fontWeight = FontWeight.Bold,
                color = DjamiGoldDark
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (hasFemaleVoice) "✓ Voix féminine française active" else "ℹ Voix française standard",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasFemaleVoice) DjamiGreenDark else DjamiTextSecondary
                    )
                }

                Column {
                    Text(text = "Débit de parole (${String.format("%.1f", speechRate)}x)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Slider(
                        value = speechRate,
                        onValueChange = onRateChange,
                        valueRange = 0.6f..1.6f,
                        colors = SliderDefaults.colors(thumbColor = DjamiGoldDark, activeTrackColor = DjamiGold)
                    )
                }

                Column {
                    Text(text = "Tonalité vocale (${String.format("%.2f", speechPitch)})", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Slider(
                        value = speechPitch,
                        onValueChange = onPitchChange,
                        valueRange = 0.7f..1.4f,
                        colors = SliderDefaults.colors(thumbColor = DjamiGoldDark, activeTrackColor = DjamiGold)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = onTestVoice) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tester la voix", fontSize = 12.sp)
                    }

                    OutlinedButton(onClick = onOpenSystemSettings) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Moteur TTS", fontSize = 12.sp)
                    }
                }
            }
        }
    )
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
