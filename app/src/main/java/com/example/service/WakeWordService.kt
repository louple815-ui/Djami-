package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.MainActivity
import com.example.speech.AudioCoordinator
import java.text.Normalizer
import java.util.Locale

/**
 * Service d'arrière-plan avec notification de premier plan pour la veille vocale « Hey DJAMI ».
 * Gère une session SpeechRecognizer dédiée au mot-clé d'activation en exclusion mutuelle
 * stricte avec l'écoute manuelle et le TTS, afin que le microphone ne soit jamais disputé.
 */
class WakeWordService : Service() {

    private val tag = "DJAMI_WAKE"
    private val channelId = "DJAMI_WAKE_CHANNEL"
    private val notificationId = 2001
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isDestroyed = false
    private var pausedForInteraction = false
    private var restartRunnable: Runnable? = null
    private var serviceIsForeground = false
    private var preferOnDeviceRecognizer: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        AudioCoordinator.setWakeWordEnabled(true)
        AudioCoordinator.registerWakeWordControls(
            pause = { pauseListening() },
            resume = { resumeListening() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Ne pas redémarrer silencieusement si Android tue le processus
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP_SERVICE -> {
                pausedForInteraction = true
                cancelPendingRestart()
                stopRecognitionSession()
                getSharedPreferences("djami_preferences", Context.MODE_PRIVATE)
                    .edit().putBoolean("wake_word_active", false).apply()
                AudioCoordinator.setWakeWordEnabled(false)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START_PAUSED -> {
                if (!ensureForegroundService()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                pausedForInteraction = true
                cancelPendingRestart()
                stopRecognitionSession()
                updateNotification("Veille prête — activation de l'écoute en cours")
                return START_NOT_STICKY
            }
            ACTION_ACK_WAKE -> {
                updateNotification("Dites « Hey DJAMI » pour me réveiller")
                return START_NOT_STICKY
            }
            ACTION_PAUSE_LISTENING -> {
                pauseListening()
                return START_NOT_STICKY
            }
            ACTION_RESUME_LISTENING -> {
                resumeListening()
                return START_NOT_STICKY
            }
        }

        // Démarrage normal ordonné par l'utilisateur
        pausedForInteraction = false
        if (!ensureForegroundService()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startListeningLoop()
        return START_STICKY
    }

    private fun pauseListening() {
        mainHandler.post {
            pausedForInteraction = true
            cancelPendingRestart()
            stopRecognitionSession()
            updateNotification("Veille suspendue pendant l'interaction")
            Log.d(tag, "Veille vocale suspendue.")
        }
    }

    private fun resumeListening() {
        mainHandler.post {
            if (isDestroyed) return@post
            if (!ensureForegroundService()) return@post
            pausedForInteraction = false
            updateNotification("Dites « Hey DJAMI » pour me réveiller")
            startListeningLoop()
            Log.d(tag, "Reprise de l'écoute en veille.")
        }
    }

    private fun ensureForegroundService(): Boolean {
        if (serviceIsForeground) return true
        return try {
            val notification = buildForegroundNotification("Dites « Hey DJAMI » pour me réveiller")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(notificationId, notification)
            }
            serviceIsForeground = true
            true
        } catch (e: Exception) {
            Log.e(tag, "Impossible de démarrer le service vocal de premier plan", e)
            false
        }
    }

    private fun buildForegroundNotification(message: String, wakeText: String? = null): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (wakeText != null) {
                putExtra(EXTRA_WAKE_TRIGGERED, true)
                putExtra(EXTRA_WAKE_TEXT, wakeText)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("DJAMI • Veille vocale")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter la veille", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String, wakeText: String? = null) {
        if (!serviceIsForeground) return
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(notificationId, buildForegroundNotification(message, wakeText))
        } catch (e: Exception) {
            Log.w(tag, "Mise à jour de la notification impossible: ${e.message}")
        }
    }

    private fun startListeningLoop() {
        if (isDestroyed || pausedForInteraction || isListening) return
        cancelPendingRestart()

        mainHandler.post {
            if (isDestroyed || pausedForInteraction || isListening) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.e(tag, "Aucun service de reconnaissance vocale disponible pour la veille.")
                updateNotification("Moteur vocal indisponible — ouvrez DJAMI pour vérifier")
                scheduleRestart(RETRY_DELAY_MS * 3)
                return@post
            }

            try {
                val recognizer = createRecognizer()
                speechRecognizer = recognizer
                recognizer.setRecognitionListener(createWakeRecognitionListener(recognizer))

                val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                }

                isListening = true
                recognizer.startListening(recognitionIntent)
                Log.d(tag, "Écoute du mot-clé démarrée.")
            } catch (e: Exception) {
                isListening = false
                Log.e(tag, "Démarrage de l'écoute du réveil impossible", e)
                stopRecognitionSession()
                updateNotification("Écoute indisponible — ouvrez DJAMI pour réessayer")
                scheduleRestart(RETRY_DELAY_MS)
            }
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val useOnDevice = preferOnDeviceRecognizer ?: (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        try {
                            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                        } catch (e: Exception) {
                            false
                        }
                )
        preferOnDeviceRecognizer = useOnDevice

        return if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } catch (e: Exception) {
                Log.w(tag, "Moteur local indisponible; utilisation du moteur système: ${e.message}")
                preferOnDeviceRecognizer = false
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun createWakeRecognitionListener(owner: SpeechRecognizer) = object : RecognitionListener {
        private fun isCurrent(): Boolean = !isDestroyed && speechRecognizer === owner && !pausedForInteraction

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (isCurrent()) isListening = false
        }

        override fun onError(error: Int) {
            if (!isCurrent()) return
            isListening = false
            Log.w(tag, "Erreur de veille vocale ($error); reprise contrôlée.")

            if (preferOnDeviceRecognizer == true && isLocalFallbackError(error)) {
                preferOnDeviceRecognizer = false
                stopRecognitionSession()
                scheduleRestart(500L)
                return
            }
            stopRecognitionSession()
            scheduleRestart(retryDelayFor(error))
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent()) return
            isListening = false
            val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val matchedText = candidates.firstOrNull { isWakeWordDetected(it) }

            if (matchedText != null) {
                // Filtre anti-écho
                if (AudioCoordinator.isSelfVoiceEcho(matchedText)) {
                    Log.d(tag, "Écho DJAMI ignoré en veille.")
                    stopRecognitionSession()
                    scheduleRestart(1000L)
                    return
                }
                onWakeWordDetected(matchedText)
            } else {
                stopRecognitionSession()
                scheduleRestart(NORMAL_RESTART_DELAY_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent()) return
            val candidates = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val matchedText = candidates.firstOrNull { isWakeWordDetected(it) }
            if (matchedText != null && !AudioCoordinator.isSelfVoiceEcho(matchedText)) {
                onWakeWordDetected(matchedText)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun isWakeWordDetected(text: String): Boolean {
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized.matches(Regex(".*\\b(hey|he|eh|ok|okay|bonjour)\\s+(djami|jami|dami)\\b.*")) ||
                normalized.matches(Regex("(djami|jami|dami)"))
    }

    private fun onWakeWordDetected(rawText: String) {
        if (isDestroyed || pausedForInteraction) return
        pausedForInteraction = true
        isListening = false
        cancelPendingRestart()
        stopRecognitionSession()
        vibrateFeedback()
        updateNotification("DJAMI réveillé — appuyez pour parler", rawText)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WAKE_TRIGGERED, true)
            putExtra(EXTRA_WAKE_TEXT, rawText)
        }
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w(tag, "Android a empêché l'ouverture automatique de DJAMI: ${e.message}")
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (isDestroyed || pausedForInteraction || restartRunnable != null) return
        val task = Runnable {
            restartRunnable = null
            if (!isDestroyed && !pausedForInteraction && !isListening) {
                startListeningLoop()
            }
        }
        restartRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelPendingRestart() {
        val pending = restartRunnable
        if (pending != null) mainHandler.removeCallbacks(pending)
        restartRunnable = null
    }

    private fun isLocalFallbackError(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        12, // ERROR_LANGUAGE_NOT_SUPPORTED
        13 -> true // ERROR_LANGUAGE_UNAVAILABLE
        else -> false
    }

    private fun retryDelayFor(error: Int): Long = when (error) {
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_CLIENT -> 2_500L
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> 8_000L
        else -> RETRY_DELAY_MS
    }

    private fun stopRecognitionSession() {
        isListening = false
        val recognizer = speechRecognizer
        speechRecognizer = null
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            Log.d(tag, "Annulation de la veille ignorée: ${e.message}")
        }
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(tag, "Destruction du recognizer de veille impossible: ${e.message}")
        }
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(120L)
            }
        } catch (e: Exception) {
            Log.w(tag, "Vibration de confirmation indisponible: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Veille vocale DJAMI",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification visible pendant l'activation de la veille vocale DJAMI"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        pausedForInteraction = true
        cancelPendingRestart()
        mainHandler.removeCallbacksAndMessages(null)
        stopRecognitionSession()

        AudioCoordinator.unregisterWakeWordControls()
        AudioCoordinator.setWakeWordEnabled(false)

        if (serviceIsForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Exception) {
                Log.w(tag, "Arrêt de la notification de premier plan impossible: ${e.message}")
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP_SERVICE = "com.example.service.ACTION_STOP_SERVICE"
        const val ACTION_ACK_WAKE = "com.example.service.ACTION_ACK_WAKE"
        const val ACTION_START_PAUSED = "com.example.service.ACTION_START_PAUSED"
        const val ACTION_PAUSE_LISTENING = "com.example.service.ACTION_PAUSE_LISTENING"
        const val ACTION_RESUME_LISTENING = "com.example.service.ACTION_RESUME_LISTENING"
        const val EXTRA_WAKE_TRIGGERED = "extra_wake_triggered"
        const val EXTRA_WAKE_TEXT = "extra_wake_text"
        private const val NORMAL_RESTART_DELAY_MS = 800L
        private const val RETRY_DELAY_MS = 2_500L
    }
}
