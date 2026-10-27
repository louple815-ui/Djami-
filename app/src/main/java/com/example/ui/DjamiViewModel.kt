package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.CommandResult
import com.example.engine.HybridAiEngine
import com.example.engine.LocalCommandManager
import com.example.service.WakeWordService
import com.example.speech.AudioCoordinator
import com.example.speech.RecognitionState
import com.example.speech.SpeechRecognizerManager
import com.example.speech.TtsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DjamiStatusType {
    READY,
    LISTENING,
    PROCESSING,
    SPEAKING,
    STOPPED,
    ERROR
}

data class DjamiUiState(
    val status: DjamiStatusType = DjamiStatusType.READY,
    val statusMessage: String = "Prêt",
    val recognizedTranscription: String = "",
    val assistantResponse: String = "Bonjour ! Je suis DJAMI. Appuyez sur le micro ou écrivez un message pour commencer.",
    val isOfflineMode: Boolean = false,
    val isWakeWordActive: Boolean = false,
    val isSpeaking: Boolean = false,
    val hasFemaleVoice: Boolean = false,
    val isFrenchVoiceAvailable: Boolean = true,
    val canRetryListening: Boolean = false,
    val diagnosticLogs: List<String> = emptyList(),
    val missingPermissions: List<String> = emptyList(),
    val speechPitch: Float = 1.08f,
    val speechRate: Float = 1.0f
)

class DjamiViewModel(application: Application) : AndroidViewModel(application) {

    private val wakePreferences = application.getSharedPreferences("djami_preferences", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        DjamiUiState(isWakeWordActive = wakePreferences.getBoolean("wake_word_active", false))
    )
    val uiState: StateFlow<DjamiUiState> = _uiState.asStateFlow()

    private val localCommandManager = LocalCommandManager(application)
    private val aiEngine = HybridAiEngine(application)
    val ttsManager = TtsManager(application)

    private var speechRecognizerManager: SpeechRecognizerManager? = null
    private var lastGeneratedSpeech: String = ""
    private var wakeActivationPending = false

    init {
        logDiagnostic("Initialisation du système DJAMI avec coordinateur audio...")
        setupSpeechRecognizer()

        // Synchronisation de l'état du TTS
        viewModelScope.launch {
            ttsManager.isSpeaking.collect { speaking ->
                _uiState.update {
                    it.copy(
                        isSpeaking = speaking,
                        status = if (speaking) DjamiStatusType.SPEAKING
                        else if (it.status == DjamiStatusType.SPEAKING) DjamiStatusType.READY
                        else it.status,
                        statusMessage = if (speaking) "DJAMI parle…"
                        else if (it.status == DjamiStatusType.SPEAKING) "Prêt"
                        else it.statusMessage
                    )
                }
            }
        }

        // Synchronisation stricte de l'état réel de la veille Hey DJAMI
        viewModelScope.launch {
            AudioCoordinator.isWakeWordEnabled.collect { isEnabled ->
                _uiState.update { it.copy(isWakeWordActive = isEnabled) }
            }
        }

        viewModelScope.launch {
            ttsManager.hasFemaleVoice.collect { hasFemale ->
                _uiState.update { it.copy(hasFemaleVoice = hasFemale) }
            }
        }

        viewModelScope.launch {
            ttsManager.frenchVoiceAvailable.collect { frAvailable ->
                _uiState.update { it.copy(isFrenchVoiceAvailable = frAvailable) }
                if (!frAvailable) {
                    logDiagnostic("Avertissement: Données vocales françaises non détectées sur le système.")
                }
            }
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizerManager = SpeechRecognizerManager(
            context = getApplication(),
            onFinalResult = { text ->
                logDiagnostic("STT Résultat validé: \"$text\"")
                val clean = text.trim()
                if (clean.isBlank()) {
                    _uiState.update {
                        it.copy(
                            recognizedTranscription = "",
                            status = DjamiStatusType.READY,
                            statusMessage = "Aucune parole détectée",
                            canRetryListening = true
                        )
                    }
                    displayAndSpeakResponse("Pardon, je n'ai pas bien compris ce que vous avez dit. Pouvez-vous répéter ?")
                } else {
                    _uiState.update {
                        it.copy(
                            recognizedTranscription = clean,
                            status = DjamiStatusType.PROCESSING,
                            statusMessage = "Traitement de votre demande…",
                            canRetryListening = false
                        )
                    }
                    processUserInput(clean)
                }
            },
            onPartialResult = { partial ->
                _uiState.update {
                    it.copy(
                        recognizedTranscription = partial,
                        status = DjamiStatusType.LISTENING,
                        statusMessage = "Écoute en direct…"
                    )
                }
            }
        )

        viewModelScope.launch {
            speechRecognizerManager?.state?.collect { state ->
                when (state) {
                    is RecognitionState.Idle -> {
                        if (_uiState.value.status == DjamiStatusType.LISTENING) {
                            _uiState.update { it.copy(status = DjamiStatusType.READY, statusMessage = "Prêt") }
                        }
                    }
                    is RecognitionState.Ready -> {
                        _uiState.update { it.copy(status = DjamiStatusType.LISTENING, statusMessage = "🎙️ Je vous écoute…") }
                    }
                    is RecognitionState.Listening -> {
                        _uiState.update { it.copy(status = DjamiStatusType.LISTENING, statusMessage = "🎙️ Écoute en cours…") }
                    }
                    is RecognitionState.Processing -> {
                        _uiState.update { it.copy(status = DjamiStatusType.PROCESSING, statusMessage = "Analyse des paroles…") }
                    }
                    is RecognitionState.Error -> {
                        logDiagnostic("Erreur STT [${state.errorType}]: ${state.message}")
                        _uiState.update {
                            it.copy(
                                status = DjamiStatusType.ERROR,
                                statusMessage = state.message,
                                canRetryListening = state.canRetry
                            )
                        }
                        resumeWakeWordServiceIfActive()
                    }
                    is RecognitionState.Success -> {
                        // Traité via onFinalResult
                    }
                }
            }
        }
    }

    fun startListening() {
        val wakeModeWasActive = _uiState.value.isWakeWordActive
        wakeActivationPending = false
        pauseWakeWordService()
        ttsManager.stop()
        logDiagnostic("Démarrage manuel de l'écoute microphone")

        if (wakeModeWasActive) {
            viewModelScope.launch {
                delay(250L)
                speechRecognizerManager?.startListening()
            }
        } else {
            speechRecognizerManager?.startListening()
        }
    }

    fun stopListening() {
        speechRecognizerManager?.stopListening()
    }

    fun retryListening() {
        logDiagnostic("Nouvelle tentative d'écoute microphone...")
        _uiState.update { it.copy(status = DjamiStatusType.READY, statusMessage = "Nouvelle tentative…") }
        speechRecognizerManager?.retryListening()
    }

    /**
     * Appelé par MainActivity lors de la détection du mot-clé « Hey DJAMI ».
     */
    fun onWakeWordActivated(rawText: String) {
        logDiagnostic("Mot de réveil détecté: $rawText")
        wakeActivationPending = true
        setWakeWordPreference(true)
        AudioCoordinator.setWakeWordEnabled(true)
        _uiState.update { it.copy(isWakeWordActive = true) }

        val app = getApplication<Application>()
        val ackIntent = Intent(app, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_ACK_WAKE
        }
        try {
            app.startService(ackIntent)
        } catch (e: Exception) {
            logDiagnostic("Impossible d'accuser réception du mot de réveil: ${e.message}")
        }

        pauseWakeWordService()
        ttsManager.stop()

        // Parle d'abord pour confirmer le réveil, puis démarre l'écoute après la parole
        // Ceci évite que le micro n'entende la voix de DJAMI ou que deux recognizers soient en conflit
        ttsManager.speak("Oui, je vous écoute.") {
            wakeActivationPending = false
            startListening()
        }
    }

    fun processUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            displayAndSpeakResponse("Pardon, je n'ai pas entendu de demande. Pouvez-vous répéter ?")
            return
        }
        pauseWakeWordService()

        logDiagnostic("Traitement de la requête: \"$trimmed\"")
        _uiState.update {
            it.copy(
                recognizedTranscription = trimmed,
                status = DjamiStatusType.PROCESSING,
                statusMessage = "Recherche de la meilleure réponse…"
            )
        }

        viewModelScope.launch {
            // 1. Essai avec le gestionnaire de commandes locales
            val commandResult = localCommandManager.processCommand(trimmed)

            if (commandResult is CommandResult.Handled) {
                logDiagnostic("Commande locale reconnue: ${commandResult.actionDescription}")

                if (commandResult.isStopSpeech) {
                    stopSpeech()
                    _uiState.update {
                        it.copy(
                            status = DjamiStatusType.STOPPED,
                            statusMessage = "Arrêté",
                            assistantResponse = "Synthèse vocale interrompue."
                        )
                    }
                    return@launch
                }

                if (commandResult.isRepeat) {
                    repeatLastResponse()
                    return@launch
                }

                val requestedWakeToggle = commandResult.toggleWakeWord
                if (requestedWakeToggle != null) {
                    if (requestedWakeToggle) {
                        val wasActive = _uiState.value.isWakeWordActive
                        _uiState.update { it.copy(isWakeWordActive = true) }
                        setWakeWordPreference(true)
                        AudioCoordinator.setWakeWordEnabled(true)
                        if (!wasActive) startWakeWordService(startPaused = true)
                    } else {
                        stopWakeWordService()
                        _uiState.update { it.copy(isWakeWordActive = false) }
                        setWakeWordPreference(false)
                        AudioCoordinator.setWakeWordEnabled(false)
                    }
                }

                if (commandResult.intentToLaunch != null) {
                    try {
                        commandResult.intentToLaunch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        getApplication<Application>().startActivity(commandResult.intentToLaunch)
                    } catch (e: Exception) {
                        logDiagnostic("Erreur lancement application: ${e.message}")
                    }
                }

                displayAndSpeakResponse(commandResult.responseSpeech)
            } else {
                // 2. Si aucune commande locale ne correspond, passer à l'IA (Hors-ligne ou Gemini)
                logDiagnostic("Transmission au moteur IA (Mode hors-ligne=${_uiState.value.isOfflineMode})")
                val response = aiEngine.generateResponse(trimmed, forceOffline = _uiState.value.isOfflineMode)
                displayAndSpeakResponse(response)
            }
        }
    }

    private fun displayAndSpeakResponse(text: String, afterSpeech: (() -> Unit)? = null) {
        if (text.isBlank()) {
            afterSpeech?.invoke() ?: resumeWakeWordServiceIfActive()
            return
        }
        lastGeneratedSpeech = text
        logDiagnostic("Génération réponse (${text.length} car): déclenchement TTS")

        _uiState.update {
            it.copy(
                assistantResponse = text,
                status = DjamiStatusType.SPEAKING,
                statusMessage = "DJAMI vous répond"
            )
        }

        ttsManager.speak(text) {
            if (afterSpeech != null) {
                afterSpeech.invoke()
            } else {
                resumeWakeWordServiceIfActive()
            }
        }
    }

    fun repeatLastResponse() {
        if (lastGeneratedSpeech.isNotBlank()) {
            logDiagnostic("Répétition de la réponse précédente")
            _uiState.update {
                it.copy(
                    status = DjamiStatusType.SPEAKING,
                    statusMessage = "Répétition…"
                )
            }
            ttsManager.speak(lastGeneratedSpeech) { resumeWakeWordServiceIfActive() }
        } else {
            ttsManager.speak("Je n'ai pas encore de réponse à répéter.") { resumeWakeWordServiceIfActive() }
        }
    }

    fun stopSpeech() {
        logDiagnostic("Arrêt manuel de la voix")
        ttsManager.stop()
        AudioCoordinator.onAudioStopped()
        _uiState.update {
            it.copy(
                status = DjamiStatusType.STOPPED,
                statusMessage = "Arrêté"
            )
        }
        resumeWakeWordServiceIfActive()
    }

    fun toggleOfflineMode(offline: Boolean) {
        _uiState.update { it.copy(isOfflineMode = offline) }
        logDiagnostic("Mode basculé: ${if (offline) "Hors-ligne strict" else "En ligne (Gemini IA)"}")
        val msg = if (offline) "Mode hors-ligne activé." else "Mode en ligne activé."
        pauseWakeWordService()
        ttsManager.speak(msg) { resumeWakeWordServiceIfActive() }
    }

    fun toggleWakeWord(active: Boolean) {
        if (active == _uiState.value.isWakeWordActive) {
            setWakeWordPreference(active)
            AudioCoordinator.setWakeWordEnabled(active)
            if (active) resumeWakeWordServiceIfActive() else stopWakeWordService()
            return
        }

        if (active) {
            _uiState.update { it.copy(isWakeWordActive = true) }
            setWakeWordPreference(true)
            AudioCoordinator.setWakeWordEnabled(true)
            logDiagnostic("Activation demandée pour la veille Hey DJAMI")
            // Démarre le service en pause pour qu'il n'entende pas la confirmation vocale
            if (startWakeWordService(startPaused = true)) {
                ttsManager.speak("Veille vocale activée.") { resumeWakeWordServiceIfActive() }
            }
        } else {
            stopWakeWordService()
            _uiState.update { it.copy(isWakeWordActive = false) }
            setWakeWordPreference(false)
            AudioCoordinator.setWakeWordEnabled(false)
            logDiagnostic("Service de veille désactivé")
            ttsManager.speak("Veille vocale désactivée.")
        }
    }

    private fun startWakeWordService(startPaused: Boolean = false): Boolean {
        val app = getApplication<Application>()
        val serviceIntent = Intent(app, WakeWordService::class.java).apply {
            if (startPaused) action = WakeWordService.ACTION_START_PAUSED
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(serviceIntent)
            } else {
                app.startService(serviceIntent)
            }
            logDiagnostic(if (startPaused) "Service de veille préparé en pause" else "Service de veille Hey DJAMI démarré")
            true
        } catch (e: Exception) {
            logDiagnostic("Échec du démarrage de la veille vocale: ${e.message}")
            setWakeWordPreference(false)
            AudioCoordinator.setWakeWordEnabled(false)
            _uiState.update {
                it.copy(
                    isWakeWordActive = false,
                    status = DjamiStatusType.ERROR,
                    statusMessage = "Impossible d'activer la veille: ${e.localizedMessage}"
                )
            }
            false
        }
    }

    private fun stopWakeWordService() {
        val app = getApplication<Application>()
        try {
            app.stopService(Intent(app, WakeWordService::class.java))
        } catch (e: Exception) {
            logDiagnostic("Erreur à l'arrêt de la veille vocale: ${e.message}")
        }
    }

    private fun pauseWakeWordService() {
        if (!_uiState.value.isWakeWordActive) return
        val app = getApplication<Application>()
        try {
            app.startService(
                Intent(app, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_PAUSE_LISTENING
                }
            )
        } catch (e: Exception) {
            logDiagnostic("Suspension temporaire de la veille impossible: ${e.message}")
        }
    }

    private fun resumeWakeWordServiceIfActive() {
        if (!_uiState.value.isWakeWordActive) return
        val app = getApplication<Application>()
        try {
            app.startService(
                Intent(app, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_RESUME_LISTENING
                }
            )
        } catch (e: Exception) {
            logDiagnostic("Reprise de la veille impossible: ${e.message}")
        }
    }

    private fun setWakeWordPreference(active: Boolean) {
        wakePreferences.edit().putBoolean("wake_word_active", active).apply()
    }

    fun syncWakeWordStateFromPreferences() {
        val enabled = wakePreferences.getBoolean("wake_word_active", false)
        _uiState.update { it.copy(isWakeWordActive = enabled) }
        AudioCoordinator.setWakeWordEnabled(enabled)
        if (enabled && !wakeActivationPending) {
            resumeWakeWordServiceIfActive()
        }
    }

    fun setSpeechRate(rate: Float) {
        ttsManager.speechRate = rate
        _uiState.update { it.copy(speechRate = rate) }
    }

    fun setSpeechPitch(pitch: Float) {
        ttsManager.speechPitch = pitch
        _uiState.update { it.copy(speechPitch = pitch) }
    }

    fun openTtsSettings() {
        ttsManager.openTtsSettings()
    }

    fun logDiagnostic(entry: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.FRENCH).format(Date())
        val formatted = "[$time] $entry"
        _uiState.update {
            it.copy(diagnosticLogs = (listOf(formatted) + it.diagnosticLogs).take(30))
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizerManager?.destroy()
        ttsManager.destroy()
    }
}
