package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AiEngine {
    suspend fun generateResponse(prompt: String): String
}

class OfflineAiEngine : AiEngine {

    private val conversationalRules = mapOf(
        "bonjour" to "Bonjour ! C'est un plaisir de vous aider. Que puis-je faire pour vous aujourd'hui ?",
        "salut" to "Salut ! Comment puis-je vous être utile ?",
        "coucou" to "Coucou ! DJAMI à votre écoute.",
        "comment vas-tu" to "Je vais très bien, merci ! Toujours prête à vous assister.",
        "comment tu vas" to "Tout va pour le mieux ! Et vous, comment se passe votre journée ?",
        "ça va" to "Ça va super bien ! Qu'allons-nous accomplir ensemble ?",
        "merci" to "Je vous en prie ! C'est un plaisir de vous aider.",
        "de rien" to "Avec grand plaisir !",
        "au revoir" to "Au revoir ! Passez une excellente journée.",
        "bonne nuit" to "Bonne nuit et reposez-vous bien !",
        "météo" to "En mode hors-ligne, je ne peux pas interroger les satellites météo. Pour la météo en direct, activez le mode en ligne ou dites 'ouvre mon navigateur'.",
        "aide" to "Vous pouvez me demander l'heure, la date, ouvrir WhatsApp ou YouTube, lancer l'appareil photo, raconter une blague, ou me poser des questions en mode en ligne !"
    )

    override suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.Default) {
        val clean = prompt.lowercase().trim()
        
        for ((trigger, response) in conversationalRules) {
            if (clean.contains(trigger)) {
                return@withContext response
            }
        }

        // Réponse générale hors-ligne enrichie
        "En mode hors-ligne, j'ai bien reçu : \"$prompt\". Pour des réponses encyclopédiques détaillées ou des recherches avancées, vous pouvez basculer en mode en ligne avec l'interrupteur ci-dessus, ou utiliser les commandes directes comme l'heure, la date ou l'ouverture d'applications."
    }
}

class HybridAiEngine(private val context: Context) : AiEngine {

    private val offlineEngine = OfflineAiEngine()
    private val TAG = "DJAMI_AI_ENGINE"

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
        if (forceOffline || !isOnline()) {
            return offlineEngine.generateResponse(prompt)
        }

        return withContext(Dispatchers.IO) {
            try {
                // Tentative avec Firebase AI / Gemini
                val generativeModel = Firebase.ai.generativeModel(
                    modelName = "gemini-2.5-flash",
                    generationConfig = generationConfig {
                        temperature = 0.7f
                        maxOutputTokens = 250
                    },
                    systemInstruction = content {
                        text("Tu es DJAMI, une assistante vocale IA francophone polie, concise, élégante et efficace pour smartphone Android. Réponds toujours en français de manière claire, sans formatage markdown lourd, adaptée à la lecture vocale.")
                    }
                )

                val response = generativeModel.generateContent(content { text(prompt) })
                val text = response.text?.trim()
                if (!text.isNullOrBlank()) {
                    text
                } else {
                    offlineEngine.generateResponse(prompt)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini online request failed or unconfigured, falling back to local engine: ${e.message}")
                offlineEngine.generateResponse(prompt)
            }
        }
    }
}
