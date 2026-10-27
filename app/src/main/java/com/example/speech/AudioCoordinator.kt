package com.example.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Gestionnaire audio centralisé pour DJAMI.
 * Garantit l'exclusion mutuelle stricte entre :
 * - La reconnaissance manuelle (bouton "Parler")
 * - La veille vocale en arrière-plan ("Hey DJAMI")
 * - La synthèse vocale (Text-To-Speech)
 *
 * Évite les conflits de microphone, les doubles instances et empêche DJAMI
 * de reconnaître sa propre voix et de créer une boucle d'écho infinie.
 */
object AudioCoordinator {

    private const val TAG = "DJAMI_AUDIO_COORD"
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class AudioMode {
        IDLE,
        MANUAL_LISTENING,
        WAKE_WORD_LISTENING,
        SPEAKING,
        STOPPED,
        ERROR
    }

    private val _currentMode = MutableStateFlow(AudioMode.IDLE)
    val currentMode: StateFlow<AudioMode> = _currentMode.asStateFlow()

    // État réel et synchronisé de la veille vocale
    private val _isWakeWordEnabled = MutableStateFlow(false)
    val isWakeWordEnabled: StateFlow<Boolean> = _isWakeWordEnabled.asStateFlow()

    // Mémoire de la dernière phrase prononcée par DJAMI pour le filtre anti-écho
    private var lastSpokenText: String = ""
    private var lastSpeechEndTime: Long = 0L

    // Callbacks d'action coordonnés
    private var onPauseWakeWordRequested: (() -> Unit)? = null
    private var onResumeWakeWordRequested: (() -> Unit)? = null
    private var onCancelManualListeningRequested: (() -> Unit)? = null
    private var onStopTtsRequested: (() -> Unit)? = null

    fun registerWakeWordControls(
        pause: () -> Unit,
        resume: () -> Unit
    ) {
        onPauseWakeWordRequested = pause
        onResumeWakeWordRequested = resume
    }

    fun unregisterWakeWordControls() {
        onPauseWakeWordRequested = null
        onResumeWakeWordRequested = null
    }

    fun registerManualListeningControls(cancel: () -> Unit) {
        onCancelManualListeningRequested = cancel
    }

    fun registerTtsControls(stop: () -> Unit) {
        onStopTtsRequested = stop
    }

    /**
     * Appelé lorsque la veille Hey DJAMI est activée ou désactivée par l'utilisateur
     */
    fun setWakeWordEnabled(enabled: Boolean) {
        Log.d(TAG, "Veille vocale définie à : $enabled")
        _isWakeWordEnabled.value = enabled
        if (!enabled && _currentMode.value == AudioMode.WAKE_WORD_LISTENING) {
            _currentMode.value = AudioMode.IDLE
        }
    }

    /**
     * Appelé avant de démarrer l'écoute manuelle au microphone
     */
    fun onRequestManualListening(): Boolean {
        // 1. Si DJAMI est en train de parler, couper immédiatement la parole
        if (_currentMode.value == AudioMode.SPEAKING) {
            Log.d(TAG, "Arrêt du TTS avant écoute manuelle")
            onStopTtsRequested?.invoke()
        }

        // 2. Mettre en pause la veille pour éviter tout conflit sur le microphone
        onPauseWakeWordRequested?.invoke()

        _currentMode.value = AudioMode.MANUAL_LISTENING
        return true
    }

    /**
     * Appelé lorsque l'écoute manuelle s'arrête (fin de parole ou annulation)
     */
    fun onManualListeningEnded() {
        if (_currentMode.value == AudioMode.MANUAL_LISTENING) {
            _currentMode.value = AudioMode.IDLE
            scheduleResumeWakeWordIfAllowed()
        }
    }

    /**
     * Appelé dès que la synthèse vocale commence à émettre du son
     */
    fun onTtsStarted(spokenText: String) {
        Log.d(TAG, "TTS a démarré. Arrêt immédiat de toute écoute microphone.")
        lastSpokenText = spokenText.lowercase(Locale.FRENCH).trim()
        _currentMode.value = AudioMode.SPEAKING

        // Arrêt immédiat de la reconnaissance manuelle si active
        onCancelManualListeningRequested?.invoke()

        // Suspension immédiate de la veille vocale
        onPauseWakeWordRequested?.invoke()
    }

    /**
     * Appelé lorsque la synthèse vocale se termine ou est arrêtée
     */
    fun onTtsFinished() {
        Log.d(TAG, "TTS terminé.")
        lastSpeechEndTime = System.currentTimeMillis()
        if (_currentMode.value == AudioMode.SPEAKING) {
            _currentMode.value = AudioMode.IDLE
        }

        // Attente d'un délai de 400ms pour laisser s'estomper l'écho acoustique dans la pièce
        scheduleResumeWakeWordIfAllowed(delayMs = 450)
    }

    /**
     * Filtre anti-boucle : vérifie si le texte reconnu correspond à ce que DJAMI vient de dire
     */
    fun isSelfVoiceEcho(recognizedText: String): Boolean {
        val clean = recognizedText.lowercase(Locale.FRENCH).trim()
        val timeSinceSpeech = System.currentTimeMillis() - lastSpeechEndTime

        // Ignorer les phrases système standard de DJAMI si détectées récemment (< 2,5 s)
        if (timeSinceSpeech < 2500) {
            if (clean == "oui, je vous écoute" || clean == "oui je vous écoute" || clean == "j'arrête" || clean == "veille vocale activée") {
                Log.w(TAG, "Anti-écho : Phrase système détectée provenant des haut-parleurs, ignorée.")
                return true
            }

            if (lastSpokenText.isNotBlank() && (lastSpokenText.contains(clean) || clean.contains(lastSpokenText))) {
                Log.w(TAG, "Anti-écho : Écho détecté de DJAMI, ignoré.")
                return true
            }
        }
        return false
    }

    private fun scheduleResumeWakeWordIfAllowed(delayMs: Long = 300) {
        mainHandler.removeCallbacksAndMessages("RESUME_WAKE")
        mainHandler.postDelayed({
            if (_isWakeWordEnabled.value && _currentMode.value == AudioMode.IDLE) {
                Log.d(TAG, "Reprise sécurisée de la veille vocale.")
                _currentMode.value = AudioMode.WAKE_WORD_LISTENING
                onResumeWakeWordRequested?.invoke()
            }
        }, delayMs)
    }

    fun onAudioError(message: String) {
        Log.w(TAG, "Erreur audio : $message")
        _currentMode.value = AudioMode.ERROR
    }

    fun onAudioStopped() {
        _currentMode.value = AudioMode.STOPPED
    }
}
