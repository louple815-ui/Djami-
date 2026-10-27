package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestionnaire TTS unique pour l'application DJAMI.
 * Fonctionne en mode QUEUE_FLUSH avec callbacks d'achèvement et exclusion mutuelle
 * coordonnée par AudioCoordinator pour éviter que DJAMI n'entende sa propre voix.
 */
class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val tag = "DJAMI_TTS"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isDestroyed = false
    private var currentUtteranceId: String? = null
    private var currentlySpeakingUtteranceText = ""

    private data class PendingUtterance(val text: String, val onComplete: (() -> Unit)?)
    private val pendingSpeechQueue = mutableListOf<PendingUtterance>()
    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _hasFemaleVoice = MutableStateFlow(false)
    val hasFemaleVoice: StateFlow<Boolean> = _hasFemaleVoice.asStateFlow()

    private val _frenchVoiceAvailable = MutableStateFlow(true)
    val frenchVoiceAvailable: StateFlow<Boolean> = _frenchVoiceAvailable.asStateFlow()

    var speechRate: Float = 1.0f
        set(value) {
            val safeValue = value.coerceIn(0.5f, 2.0f)
            field = safeValue
            mainHandler.post { tts?.setSpeechRate(safeValue) }
        }

    var speechPitch: Float = 1.08f // Léger pitch féminin par défaut
        set(value) {
            val safeValue = value.coerceIn(0.5f, 2.0f)
            field = safeValue
            mainHandler.post { tts?.setPitch(safeValue) }
        }

    init {
        // Enregistrement auprès du coordinateur audio pour contrôle centralisé
        AudioCoordinator.registerTtsControls {
            stop()
        }
        mainHandler.post {
            if (!isDestroyed) {
                tts = TextToSpeech(context.applicationContext, this)
            }
        }
    }

    override fun onInit(status: Int) {
        if (isDestroyed) return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(tag, "Échec de l'initialisation de TextToSpeech: status=$status")
            _isInitialized.value = false
            AudioCoordinator.onAudioError("Échec de l'initialisation du moteur de synthèse vocale")
            return
        }

        val engine = tts ?: return
        try {
            // Configuration de la langue française
            val langResult = engine.setLanguage(Locale.FRANCE)
            val frenchAvailable = langResult != TextToSpeech.LANG_MISSING_DATA &&
                    langResult != TextToSpeech.LANG_NOT_SUPPORTED
            _frenchVoiceAvailable.value = frenchAvailable

            if (!frenchAvailable) {
                Log.w(tag, "Données vocales françaises absentes ou incomplètes; utilisation de la langue par défaut.")
                engine.setLanguage(Locale.getDefault())
            } else {
                Log.i(tag, "Langue française initialisée avec succès.")
            }

            // Recherche et sélection d'une voix française féminine naturelle
            configureFemaleVoiceIfAvailable(engine)

            engine.setSpeechRate(speechRate)
            engine.setPitch(speechPitch)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post {
                        if (!isDestroyed && utteranceId != null && utteranceId == currentUtteranceId) {
                            _isSpeaking.value = true
                            AudioCoordinator.onTtsStarted(currentlySpeakingUtteranceText)
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    finishUtterance(utteranceId)
                }

                override fun onError(utteranceId: String?) {
                    Log.e(tag, "Erreur lors de la lecture TTS de l'utterance: $utteranceId")
                    finishUtterance(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    mainHandler.post {
                        if (utteranceId != null && utteranceId == currentUtteranceId) {
                            currentUtteranceId = null
                            _isSpeaking.value = false
                            AudioCoordinator.onTtsFinished()
                        }
                    }
                    if (interrupted && utteranceId != null) {
                        completionCallbacks.remove(utteranceId)
                    }
                }
            })

            _isInitialized.value = true

            // En sémantique QUEUE_FLUSH, seule la requête la plus récente est conservée à l'initialisation
            val pending = synchronized(pendingSpeechQueue) {
                pendingSpeechQueue.toList().also { pendingSpeechQueue.clear() }
            }
            pending.lastOrNull()?.let { speak(it.text, it.onComplete) }
        } catch (e: Exception) {
            Log.e(tag, "Configuration du moteur vocal impossible", e)
            _isInitialized.value = false
            AudioCoordinator.onAudioError("Configuration du moteur vocal impossible: ${e.message}")
        }
    }

    private fun configureFemaleVoiceIfAvailable(engine: TextToSpeech) {
        try {
            val installedVoices: Set<Voice>? = engine.voices
            if (!installedVoices.isNullOrEmpty()) {
                val frenchVoices = installedVoices.filter { it.locale.language.equals("fr", ignoreCase = true) }
                val candidate = frenchVoices.firstOrNull { voice ->
                    val name = voice.name.lowercase(Locale.ROOT)
                    name.contains("female") ||
                            name.contains("femme") ||
                            name.contains("#female") ||
                            name.contains("fr-fr-x-vfb") || // Voix féminine Google TTS standard
                            name.contains("fr-fr-x-vfc") ||
                            name.contains("fr-fr-x-fra-local")
                } ?: frenchVoices.firstOrNull()

                if (candidate != null) {
                    engine.voice = candidate
                    val name = candidate.name.lowercase(Locale.ROOT)
                    _hasFemaleVoice.value = name.contains("female") || name.contains("femme")
                    Log.i(tag, "Voix française sélectionnée: ${candidate.name}")
                } else {
                    _hasFemaleVoice.value = false
                    Log.w(tag, "Aucune voix française installée dans le moteur TTS.")
                }
            }
        } catch (e: Exception) {
            _hasFemaleVoice.value = false
            Log.w(tag, "Sélection de voix française impossible: ${e.message}")
        }
    }

    private fun finishUtterance(utteranceId: String?) {
        mainHandler.post {
            if (isDestroyed) return@post
            if (utteranceId != null && utteranceId == currentUtteranceId) {
                currentUtteranceId = null
                _isSpeaking.value = false
                AudioCoordinator.onTtsFinished()
            }
            val callback = if (utteranceId != null) completionCallbacks.remove(utteranceId) else null
            try {
                callback?.invoke()
            } catch (e: Exception) {
                Log.e(tag, "Callback de fin de lecture en erreur", e)
            }
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank() || isDestroyed) {
            onComplete?.invoke()
            return
        }

        mainHandler.post {
            if (isDestroyed) {
                onComplete?.invoke()
                return@post
            }

            if (!_isInitialized.value || tts == null) {
                synchronized(pendingSpeechQueue) {
                    pendingSpeechQueue.clear()
                    pendingSpeechQueue.add(PendingUtterance(text, onComplete))
                }
                return@post
            }

            stopInternal(clearPending = true)
            currentlySpeakingUtteranceText = text

            val utteranceId = "DJAMI_TTS_${System.currentTimeMillis()}_${System.nanoTime() % 1000}"
            currentUtteranceId = utteranceId
            if (onComplete != null) {
                completionCallbacks[utteranceId] = onComplete
            }

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                completionCallbacks.remove(utteranceId)
                currentUtteranceId = null
                _isSpeaking.value = false
                AudioCoordinator.onTtsFinished()
                Log.e(tag, "Le moteur TTS a retourné TextToSpeech.ERROR pour: $text")
                onComplete?.invoke()
            }
        }
    }

    fun stop() {
        mainHandler.post { stopInternal(clearPending = true) }
    }

    private fun stopInternal(clearPending: Boolean) {
        currentUtteranceId = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Arrêt du TTS impossible: ${e.message}")
        }
        _isSpeaking.value = false
        AudioCoordinator.onTtsFinished()
        completionCallbacks.clear()
        if (clearPending) {
            synchronized(pendingSpeechQueue) { pendingSpeechQueue.clear() }
        }
    }

    fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Impossible d'ouvrir les paramètres TTS", e)
        }
    }

    fun destroy() {
        mainHandler.post {
            if (isDestroyed) return@post
            isDestroyed = true
            completionCallbacks.clear()
            synchronized(pendingSpeechQueue) { pendingSpeechQueue.clear() }
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {
                Log.w(tag, "Destruction du TTS impossible: ${e.message}")
            } finally {
                tts = null
                currentUtteranceId = null
                _isInitialized.value = false
                _isSpeaking.value = false
            }
        }
    }
}
