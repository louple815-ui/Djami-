package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val TAG = "DJAMI_TTS"
    private var tts: TextToSpeech? = null
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _hasFemaleVoice = MutableStateFlow(false)
    val hasFemaleVoice: StateFlow<Boolean> = _hasFemaleVoice.asStateFlow()

    private val _frenchVoiceAvailable = MutableStateFlow(true)
    val frenchVoiceAvailable: StateFlow<Boolean> = _frenchVoiceAvailable.asStateFlow()

    private val pendingSpeechQueue = mutableListOf<String>()

    var speechRate: Float = 1.0f
        set(value) {
            field = value
            tts?.setSpeechRate(value)
        }

    var speechPitch: Float = 1.08f // Léger pitch féminin par défaut
        set(value) {
            field = value
            tts?.setPitch(value)
        }

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsInstance = tts ?: return
            
            // Vérification de la disponibilité du français
            val langResult = ttsInstance.setLanguage(Locale.FRENCH)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Langue française non supportée ou données vocales manquantes: $langResult")
                _frenchVoiceAvailable.value = false
                // Repli sur la locale par défaut de l'appareil
                ttsInstance.setLanguage(Locale.getDefault())
            } else {
                _frenchVoiceAvailable.value = true
                Log.i(TAG, "Langue française initialisée avec succès.")
            }

            // Recherche et sélection d'une voix française féminine
            configureFemaleVoiceIfAvailable(ttsInstance)

            ttsInstance.setSpeechRate(speechRate)
            ttsInstance.setPitch(speechPitch)

            ttsInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    Log.e(TAG, "Erreur lors de la lecture de l'utterance: $utteranceId")
                }
            })

            _isInitialized.value = true

            // Vider les requêtes en attente
            synchronized(pendingSpeechQueue) {
                for (pending in pendingSpeechQueue) {
                    speak(pending)
                }
                pendingSpeechQueue.clear()
            }
        } else {
            Log.e(TAG, "Échec de l'initialisation de TextToSpeech: status=$status")
            _isInitialized.value = false
        }
    }

    private fun configureFemaleVoiceIfAvailable(ttsInstance: TextToSpeech) {
        try {
            val voices: Set<Voice>? = ttsInstance.voices
            if (!voices.isNullOrEmpty()) {
                val frenchVoices = voices.filter { it.locale.language.equals("fr", ignoreCase = true) }
                
                // Recherche d'une voix marquée explicitement féminine dans son nom ou ses attributs
                val femaleCandidate = frenchVoices.firstOrNull { voice ->
                    val name = voice.name.lowercase(Locale.ROOT)
                    name.contains("female") || 
                    name.contains("femme") || 
                    name.contains("#female") || 
                    name.contains("fr-fr-x-vfb") || // Voix féminine standard Google TTS
                    name.contains("fr-fr-x-vfc") ||
                    name.contains("fr-fr-x-fra-local")
                } ?: frenchVoices.firstOrNull()

                if (femaleCandidate != null) {
                    ttsInstance.voice = femaleCandidate
                    _hasFemaleVoice.value = femaleCandidate.name.lowercase(Locale.ROOT).contains("female") ||
                            femaleCandidate.name.lowercase(Locale.ROOT).contains("femme")
                    Log.i(TAG, "Voix sélectionnée: ${femaleCandidate.name} (locale=${femaleCandidate.locale})")
                } else {
                    _hasFemaleVoice.value = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'énumérer les voix TTS: ${e.message}")
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        
        if (!_isInitialized.value) {
            Log.d(TAG, "TTS pas encore prêt, mise en file d'attente de: $text")
            synchronized(pendingSpeechQueue) {
                pendingSpeechQueue.add(text)
            }
            return
        }

        stop() // Arrête toute diction en cours pour éviter les chevauchements
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val utteranceId = "DJAMI_TTS_${System.currentTimeMillis()}"
        val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (res == TextToSpeech.ERROR) {
            Log.e(TAG, "tts.speak a retourné TextToSpeech.ERROR pour: $text")
        }
    }

    fun stop() {
        try {
            tts?.stop()
            _isSpeaking.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de l'arrêt du TTS: ${e.message}")
        }
    }

    fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Impossible d'ouvrir les paramètres TTS: ${e.message}")
        }
    }

    fun destroy() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Erreur destruction TTS: ${e.message}")
        }
    }
}
