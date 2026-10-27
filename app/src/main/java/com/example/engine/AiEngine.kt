package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

interface AiEngine {
    suspend fun generateResponse(prompt: String): String
}

/**
 * Moteur IA local hors ligne pour les situations sans réseau ou choix explicite du mode hors ligne.
 * Réponses honnêtes et claires : DJAMI n'invente pas de faux résultats hors ligne.
 */
class OfflineAiEngine : AiEngine {

    private val conversationalRules = mapOf(
        "bonjour" to "Bonjour ! DJAMI est à votre écoute. Que puis-je faire pour vous aujourd'hui ?",
        "salut" to "Salut ! Comment puis-je vous aider ?",
        "coucou" to "Coucou ! DJAMI est prête à vous assister.",
        "comment vas-tu" to "Je vais très bien, merci ! Et vous, comment se passe votre journée ?",
        "comment tu vas" to "Tout va pour le mieux ! Qu'allons-nous accomplir ensemble ?",
        "ça va" to "Ça va super bien ! Comment puis-je vous être utile ?",
        "merci" to "Je vous en prie ! C'est un réel plaisir de vous aider.",
        "de rien" to "Avec grand plaisir !",
        "au revoir" to "Au revoir ! Passez une excellente journée.",
        "bonne nuit" to "Bonne nuit et reposez-vous bien !",
        "qui es-tu" to "Je suis DJAMI, votre assistante vocale et intelligence artificielle intelligente en français pour Android.",
        "météo" to "En mode hors-ligne, je ne peux pas consulter les prévisions météo en direct. Activez le mode en ligne ou dites 'ouvre mon navigateur'.",
        "aide" to "Hors ligne, je peux vous donner l'heure, la date, ouvrir vos applications (WhatsApp, YouTube, Appareil photo, Paramètres) ou calculer des opérations simples. Pour des conversations riches, activez le mode en ligne !"
    )

    override suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.Default) {
        val clean = normalizeForMatching(prompt)

        for ((trigger, response) in conversationalRules) {
            val normalizedTrigger = normalizeForMatching(trigger)
            if (clean == normalizedTrigger || clean.contains(normalizedTrigger)) {
                return@withContext response
            }
        }

        // Réponse honnête sans fausse simulation
        "Le mode hors-ligne de DJAMI est limité aux commandes locales. Activez le mode en ligne avec une connexion Internet et une clé Gemini configurée pour que je puisse analyser : « ${prompt.take(120)} »."
    }

    private fun normalizeForMatching(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9+*/\\-\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/**
 * Moteur IA hybride connecté au véritable modèle Gemini via BuildConfig.GEMINI_API_KEY.
 * Gère l'historique conversationnel glissant en mémoire (6 tours max) pour les questions de suivi
 * ("explique encore", "pourquoi ?", "et dans mon cas ?"), avec messages d'erreurs clairs en français.
 */
class HybridAiEngine(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) : AiEngine {

    private val offlineEngine = OfflineAiEngine()
    private val tag = "DJAMI_AI_ENGINE"
    private val historyMutex = Mutex()

    // Historique conversationnel temporaire en mémoire pour comprendre le contexte et les questions de suivi
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val maxHistoryTurns = 6

    private val systemInstructionPrompt =
        "Tu es DJAMI, une assistante vocale et intelligence artificielle bienveillante pour smartphone Android. " +
        "Ta langue par défaut est le français. Comprends le français standard, le français familier et courant, " +
        "les expressions d'Afrique de l'Ouest et du Burkina Faso (ex: 'y a quoi', 'c'est comment', 'faut m'expliquer', 'ça va pas'), " +
        "les phrases orales courtes, les fautes de frappe et les petites erreurs de reconnaissance vocale. " +
        "Tu comprends les questions de suivi et les ellipses basées sur le contexte récent (ex: 'explique encore', 'pourquoi ?', 'et dans mon cas ?', 'donne un exemple'). " +
        "Tes réponses doivent être directes, pertinentes, concises et faciles à écouter à voix haute sans Markdown lourd. " +
        "Si une question est véritablement ambiguë ou incomplète, pose une seule question de clarification courte et naturelle. " +
        "Ne simule pas d'actions système non exécutées."

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override suspend fun generateResponse(prompt: String): String {
        return generateResponse(prompt, forceOffline = false)
    }

    suspend fun generateResponse(prompt: String, forceOffline: Boolean): String {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) {
            return "Pardon, je n'ai pas bien entendu votre demande. Pouvez-vous répéter ?"
        }

        if (forceOffline) {
            return offlineEngine.generateResponse(cleanPrompt)
        }

        // 1. Vérification réseau
        if (!isOnline()) {
            return "Le mode en ligne est actif, mais aucune connexion Internet n'est détectée. " +
                    "Veuillez vérifier votre réseau ou basculer en mode hors-ligne pour les commandes de base."
        }

        // 2. Vérification de la clé API Gemini
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Throwable) {
            ""
        }

        if (apiKey.isNullOrBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return "La clé d'API Gemini n'est pas encore configurée. " +
                    "Veuillez ajouter votre variable GEMINI_API_KEY dans le panneau Secrets de Google AI Studio pour activer l'IA en ligne."
        }

        // 3. Appel au véritable moteur Gemini
        return withContext(Dispatchers.IO) {
            historyMutex.lock()
            try {
                val recentHistory = conversationHistory.takeLast(maxHistoryTurns)
                val responseText = queryGeminiApi(cleanPrompt, apiKey, recentHistory)

                if (responseText.isNotBlank()) {
                    conversationHistory.add(cleanPrompt to responseText)
                    while (conversationHistory.size > maxHistoryTurns) {
                        conversationHistory.removeAt(0)
                    }
                    responseText
                } else {
                    "Je n'ai pas pu formuler de réponse. Pouvez-vous reformuler votre question ?"
                }
            } catch (e: Exception) {
                Log.e(tag, "Erreur appel Gemini API", e)
                handleGeminiError(e)
            } finally {
                historyMutex.unlock()
            }
        }
    }

    private fun queryGeminiApi(
        prompt: String,
        apiKey: String,
        recentHistory: List<Pair<String, String>>
    ): String {
        // Modèles testés : gemini-2.5-flash avec repli automatique sur gemini-1.5-flash
        val models = listOf("gemini-2.5-flash", "gemini-1.5-flash")
        var lastException: Exception? = null

        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val jsonBody = buildGeminiRequestBody(prompt, recentHistory)
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody(mediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val parsed = parseGeminiResponse(bodyString)
                        if (parsed.isNotBlank()) {
                            return parsed
                        }
                    } else {
                        Log.w(tag, "Échec modèle $model (HTTP ${response.code}): $bodyString")
                        if (response.code == 403 || response.code == 400) {
                            throw GeminiApiException("Code ${response.code}: Clé d'API invalide ou requête incorrecte. $bodyString")
                        }
                        if (response.code == 429) {
                            throw GeminiApiException("Code 429: Quota de requêtes Gemini dépassé. Veuillez patienter.")
                        }
                    }
                }
            } catch (e: GeminiApiException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Tentative $model a échoué: ${e.message}")
                lastException = e
            }
        }

        throw lastException ?: RuntimeException("Échec de connexion à l'API Gemini")
    }

    private fun buildGeminiRequestBody(
        prompt: String,
        recentHistory: List<Pair<String, String>>
    ): JSONObject {
        val root = JSONObject()

        // System Instruction
        val systemInstruction = JSONObject().apply {
            val parts = JSONArray().apply {
                put(JSONObject().apply { put("text", systemInstructionPrompt) })
            }
            put("parts", parts)
        }
        root.put("system_instruction", systemInstruction)

        // Contents (Turns)
        val contents = JSONArray()

        for ((userText, modelText) in recentHistory) {
            val userContent = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", userText) })
                })
            }
            contents.put(userContent)

            val modelContent = JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", modelText) })
                })
            }
            contents.put(modelContent)
        }

        // Current user prompt
        val currentContent = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            })
        }
        contents.put(currentContent)
        root.put("contents", contents)

        // Generation Config
        val config = JSONObject().apply {
            put("temperature", 0.5)
            put("maxOutputTokens", 600)
        }
        root.put("generationConfig", config)

        return root
    }

    private fun parseGeminiResponse(json: String): String {
        return try {
            val obj = JSONObject(json)
            val candidates = obj.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val first = candidates.getJSONObject(0)
            val content = first.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) return ""
            val text = parts.getJSONObject(0).optString("text", "")
            cleanMarkdownForVoice(text)
        } catch (e: Exception) {
            Log.e(tag, "Erreur parsing JSON Gemini", e)
            ""
        }
    }

    private fun cleanMarkdownForVoice(text: String): String {
        return text
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1") // supprime le gras
            .replace(Regex("\\*(.*?)\\*"), "$1")     // supprime l'italique
            .replace(Regex("`{1,3}.*?`{1,3}"), "")   // supprime code inline
            .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "") // supprime les titres markdown
            .replace(Regex("^[\\-*•]\\s+", RegexOption.MULTILINE), "") // supprime les puces
            .trim()
    }

    private fun handleGeminiError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Code 403") || msg.contains("Code 400") ->
                "La clé d'API Gemini semble invalide ou non autorisée. Vérifiez votre clé dans le panneau Secrets de Google AI Studio."
            msg.contains("Code 429") ->
                "Le quota de requêtes Gemini est temporairement atteint. Veuillez patienter une minute avant de poser votre prochaine question."
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ->
                "La connexion aux serveurs de l'IA a expiré ou est instable. Vérifiez votre connexion Internet et réessayez."
            else ->
                "Une difficulté est survenue lors de l'accès à l'intelligence Gemini : ${e.localizedMessage ?: "erreur réseau"}. En mode hors ligne, les commandes de base restent disponibles."
        }
    }

    fun clearConversationHistory() {
        conversationHistory.clear()
    }

    class GeminiApiException(message: String) : RuntimeException(message)
}
