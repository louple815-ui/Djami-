package com.example.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.CommandResult
import com.example.engine.HybridAiEngine
import com.example.engine.LocalCommandManager
import com.example.service.WakeWordService
import com.example.speech.RecognitionState
import com.example.speech.SpeechRecognizerManager
import com.example.speech.TtsManager
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
    ERROR
}

data class DjamiUiState(
    val status: DjamiStatusType = DjamiStatusType.READY,
    val statusMessage: String = "Prêt",
    val recognizedTranscription: String = "",
    val assistantResponse: String = "Bonjour ! Je suis DJAMI. Appuyez sur le micro ou écrivez un message pour commencer.",
    val isOfflineMode: Boolean = true,
    val isWakeWordActive: Boolean = false,
    val isSpeaking: Boolean = false,
    val hasFemaleVoice: Boolean = false,
    val isFrenchVoiceAvailable: Boolean = true,
    val diagnosticLogs: List<String> = emptyList(),
    val missingPermissions: List<String> = emptyList(),
    val speechPitch: Float = 1.08f,
    val speechRate: Float = 1.0f
)

class DjamiViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DjamiUiState())
    val uiState: StateFlow<DjamiUiState> = _uiState.asStateFlow()

    private val localCommandManager = LocalCommandManager(application)
    private val aiEngine = HybridAiEngine(application)
    val ttsManager = TtsManager(application)
    
    private var speechRecognizerManager: SpeechRecognizerManager? = null
    private var lastGeneratedSpeech: String = ""

    init {
        logDiagnostic("Initialisation de DJAMI...")
        setupSpeechRecognizer()

        viewModelScope.launch {
            ttsManager.isSpeaking.collect { speaking ->
                _uiState.update { 
                    it.copy(
                        isSpeaking = speaking,
                        status = if (speaking) DjamiStatusType.SPEAKING else if (it.status == DjamiStatusType.SPEAKING) DjamiStatusType.READY else it.status,
                        statusMessage = if (speaking) "DJAMI parle…" else if (it.status == DjamiStatusType.SPEAKING) "Prêt" else it.statusMessage
                    )
                }
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
                logDiagnostic("STT Résultat final: \"$text\"")
                _uiState.update { 
                    it.copy(
                        recognizedTranscription = text,
                        status = DjamiStatusType.PROCESSING,
                        statusMessage = "Traitement de votre demande…"
                    )
                }
                processUserInput(text)
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
                        logDiagnostic("Erreur STT (${state.errorCode}): ${state.message}")
                        _uiState.update { 
                            it.copy(
                                status = DjamiStatusType.ERROR,
                                statusMessage = state.message
                            )
                        }
                    }
                    is RecognitionState.Success -> {
                        // Géré via onFinalResult
                    }
                }
            }
        }
    }

    fun startListening() {
        ttsManager.stop()
        logDiagnostic("Démarrage du microphone")
        speechRecognizerManager?.startListening()
    }

    fun stopListening() {
        speechRecognizerManager?.stopListening()
    }

    fun processUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

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
                            status = DjamiStatusType.READY,
                            statusMessage = "Prêt",
                            assistantResponse = "Synthèse vocale interrompue."
                        )
                    }
                    return@launch
                }

                if (commandResult.isRepeat) {
                    repeatLastResponse()
                    return@launch
                }

                if (commandResult.toggleWakeWord != null) {
                    toggleWakeWord(commandResult.toggleWakeWord)
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

    private fun displayAndSpeakResponse(text: String) {
        if (text.isBlank()) return
        lastGeneratedSpeech = text
        logDiagnostic("Génération réponse (${text.length} car): déclenchement TTS")

        _uiState.update {
            it.copy(
                assistantResponse = text,
                status = DjamiStatusType.SPEAKING,
                statusMessage = "DJAMI vous répond"
            )
        }

        ttsManager.speak(text)
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
            ttsManager.speak(lastGeneratedSpeech)
        } else {
            ttsManager.speak("Je n'ai pas encore de réponse à répéter.")
        }
    }

    fun stopSpeech() {
        logDiagnostic("Arrêt manuel de la voix")
        ttsManager.stop()
        _uiState.update {
            it.copy(
                status = DjamiStatusType.READY,
                statusMessage = "Prêt"
            )
        }
    }

    fun toggleOfflineMode(offline: Boolean) {
        _uiState.update { it.copy(isOfflineMode = offline) }
        logDiagnostic("Mode basculé: ${if (offline) "Hors-ligne strict" else "En ligne (Gemini AI)"}")
        val msg = if (offline) "Mode hors-ligne activé." else "Mode en ligne activé."
        ttsManager.speak(msg)
    }

    fun toggleWakeWord(active: Boolean) {
        val app = getApplication<Application>()
        val serviceIntent = Intent(app, WakeWordService::class.java)

        if (active) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(serviceIntent)
                } else {
                    app.startService(serviceIntent)
                }
                _uiState.update { it.copy(isWakeWordActive = true) }
                logDiagnostic("Service de veille Hey DJAMI activé")
                ttsManager.speak("Veille vocale activée.")
            } catch (e: Exception) {
                logDiagnostic("Erreur démarrage veille vocale: ${e.message}")
                _uiState.update { 
                    it.copy(
                        status = DjamiStatusType.ERROR,
                        statusMessage = "Impossible d'activer la veille: ${e.localizedMessage}"
                    )
                }
            }
        } else {
            try {
                app.stopService(serviceIntent)
                _uiState.update { it.copy(isWakeWordActive = false) }
                logDiagnostic("Service de veille désactivé")
                ttsManager.speak("Veille vocale désactivée.")
            } catch (e: Exception) {
                logDiagnostic("Erreur arrêt veille: ${e.message}")
            }
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
