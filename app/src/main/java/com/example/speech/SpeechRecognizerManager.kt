package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed class RecognitionState {
    data object Idle : RecognitionState()
    data object Ready : RecognitionState()
    data object Listening : RecognitionState()
    data object Processing : RecognitionState()
    data class Success(val recognizedText: String) : RecognitionState()
    data class Error(val message: String, val errorCode: Int) : RecognitionState()
}

class SpeechRecognizerManager(
    private val context: Context,
    private val onFinalResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit = {}
) {
    private val TAG = "DJAMI_SPEECH"
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private var isCurrentlyListening = false

    val isRecognitionAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        if (!isRecognitionAvailable) {
            _state.value = RecognitionState.Error("Service de reconnaissance vocale introuvable sur l'appareil.", -1)
            return
        }

        if (isCurrentlyListening) {
            Log.d(TAG, "Reconnaissance déjà en cours, réinitialisation de l'écoute.")
            cancel()
        }

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH.toString())
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "fr-FR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                _partialText.value = ""
                isCurrentlyListening = true
                _state.value = RecognitionState.Listening
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Erreur au démarrage de l'écoute: ${e.message}")
                isCurrentlyListening = false
                _state.value = RecognitionState.Error("Impossible de démarrer le microphone: ${e.localizedMessage}", -1)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                isCurrentlyListening = false
                speechRecognizer?.stopListening()
                _state.value = RecognitionState.Processing
            } catch (e: Exception) {
                Log.e(TAG, "Erreur à l'arrêt de l'écoute: ${e.message}")
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            try {
                isCurrentlyListening = false
                speechRecognizer?.cancel()
                _state.value = RecognitionState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Erreur à l'annulation: ${e.message}")
            }
        }
    }

    private fun initRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _state.value = RecognitionState.Ready
                }

                override fun onBeginningOfSpeech() {
                    _state.value = RecognitionState.Listening
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isCurrentlyListening = false
                    _state.value = RecognitionState.Processing
                }

                override fun onError(error: Int) {
                    isCurrentlyListening = false
                    val errorMsg = mapErrorCodeToMessage(error)
                    Log.w(TAG, "Erreur de reconnaissance ($error): $errorMsg")
                    _state.value = RecognitionState.Error(errorMsg, error)
                }

                override fun onResults(results: Bundle?) {
                    isCurrentlyListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()

                    if (!text.isNullOrBlank()) {
                        _partialText.value = text
                        _state.value = RecognitionState.Success(text)
                        onFinalResult(text)
                    } else {
                        _state.value = RecognitionState.Error("Aucune parole détectée.", SpeechRecognizer.ERROR_NO_MATCH)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrBlank()) {
                        _partialText.value = text
                        onPartialResult(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun mapErrorCodeToMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Erreur d'enregistrement audio."
            SpeechRecognizer.ERROR_CLIENT -> "Erreur de client de reconnaissance vocale."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission microphone manquante."
            SpeechRecognizer.ERROR_NETWORK -> "Erreur de connexion réseau (reconnaissance vocale)."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Délai de connexion réseau dépassé."
            SpeechRecognizer.ERROR_NO_MATCH -> "Aucun mot n'a été reconnu. Essayez de parler plus près."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Le moteur vocal est occupé, réessayez."
            SpeechRecognizer.ERROR_SERVER -> "Erreur de serveur de reconnaissance vocale."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Aucun son détecté. Temps d'attente dépassé."
            else -> "Erreur de reconnaissance vocale ($error)."
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                isCurrentlyListening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "Erreur destruction recognizer: ${e.message}")
            }
        }
    }
}
