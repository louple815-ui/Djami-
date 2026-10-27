package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class RecognitionState {
    data object Idle : RecognitionState()
    data object Ready : RecognitionState()
    data object Listening : RecognitionState()
    data object Processing : RecognitionState()
    data class Success(val recognizedText: String) : RecognitionState()
    data class Error(
        val message: String,
        val errorCode: Int,
        val errorType: String,
        val canRetry: Boolean = true
    ) : RecognitionState()
}

/**
 * Gestionnaire de la reconnaissance vocale manuelle de DJAMI.
 * Sérialise toutes les interactions sur le thread principal Android,
 * privilégie le moteur local embarqué (API 31+) avec repli automatique sur le moteur système
 * si le modèle français n'est pas installé, et assure la coordination audio stricte.
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val onFinalResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit = {}
) {
    private val tag = "DJAMI_SPEECH"
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isCurrentlyListening = false
    private var isDestroyed = false
    private var usingOnDeviceRecognizer = false
    private var hasRetriedWithRemoteRecognizer = false
    private var lastStartClickTimestamp = 0L

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    val isRecognitionAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    init {
        // Enregistre les contrôles auprès du coordinateur audio
        AudioCoordinator.registerManualListeningControls {
            cancel()
        }
    }

    /**
     * Démarre une session d'écoute après avoir coordonné les flux audio.
     */
    fun startListening() {
        val now = SystemClock.elapsedRealtime()
        // Protection contre les clics rapides successifs (< 350 ms)
        if (now - lastStartClickTimestamp < 350) {
            Log.d(tag, "Clic ignoré (anti-rebond rapide).")
            return
        }
        lastStartClickTimestamp = now

        mainHandler.post {
            if (isDestroyed) return@post
            if (isCurrentlyListening) {
                Log.d(tag, "Une session d'écoute est déjà active. Réinitialisation propre.")
                cancelInternal()
            }

            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                _state.value = RecognitionState.Error(
                    message = "Aucun service de reconnaissance vocale n'est disponible sur cet appareil (service-not-allowed).",
                    errorCode = -1,
                    errorType = "service-not-allowed",
                    canRetry = false
                )
                return@post
            }

            // Notification au coordinateur audio : stoppe le TTS et suspend la veille
            AudioCoordinator.onRequestManualListening()

            _partialText.value = ""
            hasRetriedWithRemoteRecognizer = false
            startRecognizer(preferOnDevice = supportsOnDeviceRecognition())
        }
    }

    private fun supportsOnDeviceRecognition(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                try {
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
                } catch (e: Exception) {
                    Log.w(tag, "Vérification du moteur local impossible: ${e.message}")
                    false
                }

    private fun startRecognizer(preferOnDevice: Boolean) {
        if (isDestroyed) return
        destroyRecognizerOnMainThread()

        try {
            val recognizer = if (preferOnDevice && supportsOnDeviceRecognition()) {
                usingOnDeviceRecognizer = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                usingOnDeviceRecognizer = false
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(createListener(recognizer))

            val languageTag = "fr-FR"
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                // Préférence hors-ligne lorsque supportée par le moteur
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            isCurrentlyListening = true
            _state.value = RecognitionState.Listening
            recognizer.startListening(intent)
            Log.d(tag, "Reconnaissance vocale démarrée (sur appareil=$usingOnDeviceRecognizer).")
        } catch (e: Exception) {
            isCurrentlyListening = false
            Log.e(tag, "Erreur au démarrage de la reconnaissance: ${e.message}", e)
            AudioCoordinator.onManualListeningEnded()
            destroyRecognizerOnMainThread()
            _state.value = RecognitionState.Error(
                message = "Impossible de démarrer la capture audio (audio-capture) : ${e.localizedMessage}",
                errorCode = -1,
                errorType = "audio-capture",
                canRetry = true
            )
        }
    }

    private fun createListener(owner: SpeechRecognizer): RecognitionListener =
        object : RecognitionListener {
            private fun isCurrentSession(): Boolean = !isDestroyed && speechRecognizer === owner

            override fun onReadyForSpeech(params: Bundle?) {
                if (isCurrentSession()) _state.value = RecognitionState.Ready
            }

            override fun onBeginningOfSpeech() {
                if (isCurrentSession()) _state.value = RecognitionState.Listening
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!isCurrentSession()) return
                isCurrentlyListening = false
                _state.value = RecognitionState.Processing
            }

            override fun onError(error: Int) {
                if (!isCurrentSession()) return
                isCurrentlyListening = false
                AudioCoordinator.onManualListeningEnded()

                val (errorMsg, errorType) = mapErrorCodeToDetails(error)
                Log.w(tag, "Erreur de reconnaissance ($error - $errorType): $errorMsg")

                // Repli vers le moteur système si le moteur local ne dispose pas du modèle français
                val canFallback = usingOnDeviceRecognizer && !hasRetriedWithRemoteRecognizer &&
                        (error == LANGUAGE_NOT_SUPPORTED || error == LANGUAGE_UNAVAILABLE ||
                                error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_NETWORK ||
                                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)

                if (canFallback) {
                    hasRetriedWithRemoteRecognizer = true
                    Log.i(tag, "Moteur local indisponible pour le français; repli sur le moteur système.")
                    startRecognizer(preferOnDevice = false)
                    return
                }

                _state.value = RecognitionState.Error(
                    message = errorMsg,
                    errorCode = error,
                    errorType = errorType,
                    canRetry = error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                )
                destroyRecognizerOnMainThread()
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrentSession()) return
                isCurrentlyListening = false
                AudioCoordinator.onManualListeningEnded()

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()

                if (!text.isNullOrBlank()) {
                    // Filtre anti-écho : ignore la parole si elle reproduit la diction précédente de DJAMI
                    if (AudioCoordinator.isSelfVoiceEcho(text)) {
                        Log.w(tag, "Parole ignorée par le filtre anti-écho DJAMI.")
                        _state.value = RecognitionState.Idle
                        return
                    }

                    _partialText.value = text
                    _state.value = RecognitionState.Success(text)
                    onFinalResult(text)
                } else {
                    _state.value = RecognitionState.Error(
                        message = "Aucune parole n'a été détectée (no-speech). Parlez plus près du microphone.",
                        errorCode = SpeechRecognizer.ERROR_NO_MATCH,
                        errorType = "no-speech"
                    )
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrentSession()) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrBlank()) {
                    _partialText.value = text
                    onPartialResult(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    fun stopListening() {
        mainHandler.post {
            if (isDestroyed) return@post
            val recognizer = speechRecognizer
            if (recognizer == null || !isCurrentlyListening) return@post
            try {
                isCurrentlyListening = false
                _state.value = RecognitionState.Processing
                recognizer.stopListening()
            } catch (e: Exception) {
                Log.w(tag, "Arrêt de l'écoute impossible: ${e.message}")
                isCurrentlyListening = false
                AudioCoordinator.onManualListeningEnded()
            }
        }
    }

    fun cancel() {
        cancelInternal()
        AudioCoordinator.onManualListeningEnded()
    }

    private fun cancelInternal() {
        mainHandler.post {
            if (isDestroyed) return@post
            isCurrentlyListening = false
            destroyRecognizerOnMainThread()
            _state.value = RecognitionState.Idle
        }
    }

    fun retryListening() {
        cancelInternal()
        mainHandler.postDelayed({
            startListening()
        }, 200)
    }

    private fun destroyRecognizerOnMainThread() {
        val oldRecognizer = speechRecognizer
        speechRecognizer = null
        isCurrentlyListening = false
        try {
            oldRecognizer?.cancel()
        } catch (e: Exception) {
            Log.d(tag, "Annulation du recognizer ignorée: ${e.message}")
        }
        try {
            oldRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(tag, "Destruction du recognizer impossible: ${e.message}")
        }
    }

    private fun mapErrorCodeToDetails(error: Int): Pair<String, String> {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> Pair(
                "Échec de capture audio (audio-capture). Le micro est peut-être occupé par une autre application.",
                "audio-capture"
            )
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Pair(
                "Autorisation microphone manquante (not-allowed). Veuillez accorder la permission microphone dans les réglages Android.",
                "not-allowed"
            )
            SpeechRecognizer.ERROR_NETWORK -> Pair(
                "Problème de communication réseau (network). Vérifiez votre connexion Internet ou le moteur local.",
                "network"
            )
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Pair(
                "Délai d'attente réseau dépassé (network-timeout). Le serveur vocal met trop de temps à répondre.",
                "network-timeout"
            )
            SpeechRecognizer.ERROR_NO_MATCH -> Pair(
                "Aucune parole n'a été comprise (no-speech). Essayez de parler plus distinctement.",
                "no-speech"
            )
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Pair(
                "Aucun son n'a été détecté (no-speech). Parlez dès l'apparition de l'indicateur d'écoute.",
                "no-speech"
            )
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Pair(
                "Le service vocal est momentanément occupé (service-busy). Réessayez dans un instant.",
                "service-busy"
            )
            SpeechRecognizer.ERROR_SERVER -> Pair(
                "Erreur renvoyée par le serveur distant (service-not-allowed).",
                "service-not-allowed"
            )
            SpeechRecognizer.ERROR_CLIENT -> Pair(
                "Erreur du client de reconnaissance Android (client-error). Appuyez sur le micro pour réessayer.",
                "client-error"
            )
            LANGUAGE_NOT_SUPPORTED -> Pair(
                "La langue française (fr-FR) n'est pas installée sur le moteur vocal local (language-not-supported).",
                "language-not-supported"
            )
            LANGUAGE_UNAVAILABLE -> Pair(
                "Le modèle vocal français (fr-FR) est temporairement indisponible (language-unavailable).",
                "language-unavailable"
            )
            10 -> Pair(
                "Trop de requêtes vocales envoyées (too-many-requests). Veuillez patienter quelques secondes.",
                "too-many-requests"
            )
            11 -> Pair(
                "La connexion au serveur vocal a été interrompue.",
                "server-disconnected"
            )
            else -> Pair(
                "Erreur de reconnaissance vocale inattendue (code $error). Vous pouvez écrire votre demande.",
                "unknown-error"
            )
        }
    }

    fun destroy() {
        mainHandler.post {
            if (isDestroyed) return@post
            isDestroyed = true
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizerOnMainThread()
        }
    }

    companion object {
        private const val LANGUAGE_NOT_SUPPORTED = 12
        private const val LANGUAGE_UNAVAILABLE = 13
    }
}
