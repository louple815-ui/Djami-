package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class CommandResult {
    data class Handled(
        val responseSpeech: String,
        val actionDescription: String? = null,
        val intentToLaunch: Intent? = null,
        val isStopSpeech: Boolean = false,
        val isRepeat: Boolean = false,
        val toggleWakeWord: Boolean? = null
    ) : CommandResult()

    data object NotHandled : CommandResult()
}

class LocalCommandManager(private val context: Context) {

    private val jokes = listOf(
        "Pourquoi les oiseaux volent-ils vers le sud en hiver ? Parce que c'est trop loin d'y aller à pied !",
        "Que dit un informaticien quand il a froid ? Il ferme les fenêtres !",
        "Quel est le comble pour un électricien ? De ne pas être au courant !",
        "Pourquoi les plongeurs plongent-ils toujours en arrière et jamais en avant ? Parce que sinon ils tombent dans le bateau !"
    )

    private val quotes = listOf(
        "Le secret du changement, c'est de concentrer toute son énergie non pas à lutter contre le passé, mais à construire l'avenir.",
        "Chaque petite étape d'aujourd'hui construit les grandes réussites de demain.",
        "La persévérance est la clé qui ouvre toutes les portes du succès."
    )

    fun processCommand(input: String): CommandResult {
        val raw = input.trim()
        val text = raw.lowercase(Locale.FRENCH)
            .replace("djami", "")
            .replace("jami", "")
            .replace("hey", "")
            .replace("s'il te plaît", "")
            .replace("s'il vous plaît", "")
            .trim()

        // 1. Commande d'arrêt vocal
        if (text == "stop" || text.contains("arrête de parler") || text.contains("tais-toi") || text == "silence") {
            return CommandResult.Handled(
                responseSpeech = "J'arrête.",
                actionDescription = "Synthèse vocale interrompue",
                isStopSpeech = true
            )
        }

        // 2. Répétition
        if (text.contains("répète") || text.contains("repete")) {
            return CommandResult.Handled(
                responseSpeech = "",
                actionDescription = "Répétition de la dernière réponse",
                isRepeat = true
            )
        }

        // 3. Activer / Désactiver la veille vocale
        if (text.contains("active la veille") || text.contains("allume la veille") || text.contains("active l'écoute en fond")) {
            return CommandResult.Handled(
                responseSpeech = "Activation de la veille vocale.",
                actionDescription = "Veille vocale activée",
                toggleWakeWord = true
            )
        }
        if (text.contains("désactive la veille") || text.contains("desactive la veille") || text.contains("arrête la veille") || text.contains("stoppe la veille")) {
            return CommandResult.Handled(
                responseSpeech = "Veille vocale désactivée.",
                actionDescription = "Veille vocale désactivée",
                toggleWakeWord = false
            )
        }

        // 4. Heure
        if (text.contains("heure")) {
            val format = SimpleDateFormat("HH'h'mm", Locale.FRENCH)
            val heure = format.format(Date()).replace("h00", " heures précises").replace("h", " heures ")
            return CommandResult.Handled(
                responseSpeech = "Il est $heure.",
                actionDescription = "Consultation de l'heure"
            )
        }

        // 5. Date
        if (text.contains("date") || text.contains("jour") && (text.contains("quel") || text.contains("sommes"))) {
            val format = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH)
            val dateFormatee = format.format(Date())
            return CommandResult.Handled(
                responseSpeech = "Aujourd'hui, nous sommes le $dateFormatee.",
                actionDescription = "Consultation de la date"
            )
        }

        // 6. Ouvrir WhatsApp
        if (text.contains("whatsapp")) {
            val pkg = "com.whatsapp"
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            return if (launchIntent != null) {
                CommandResult.Handled(
                    responseSpeech = "J'ouvre WhatsApp.",
                    actionDescription = "Lancement de WhatsApp",
                    intentToLaunch = launchIntent
                )
            } else {
                CommandResult.Handled(
                    responseSpeech = "WhatsApp n'est pas installé sur votre téléphone.",
                    actionDescription = "Application WhatsApp introuvable"
                )
            }
        }

        // 7. Ouvrir YouTube
        if (text.contains("youtube")) {
            val pkg = "com.google.android.youtube"
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            return if (launchIntent != null) {
                CommandResult.Handled(
                    responseSpeech = "J'ouvre YouTube.",
                    actionDescription = "Lancement de YouTube",
                    intentToLaunch = launchIntent
                )
            } else {
                // Fallback web
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                CommandResult.Handled(
                    responseSpeech = "J'ouvre YouTube sur le navigateur.",
                    actionDescription = "Ouverture de YouTube Web",
                    intentToLaunch = webIntent
                )
            }
        }

        // 8. Ouvrir les paramètres du téléphone
        if (text.contains("paramètre") || text.contains("parametre") || text.contains("configuration") || text.contains("réglage")) {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return CommandResult.Handled(
                responseSpeech = "J'ouvre les paramètres du téléphone.",
                actionDescription = "Ouverture des Paramètres Android",
                intentToLaunch = intent
            )
        }

        // 9. Ouvrir l'appareil photo
        if (text.contains("appareil photo") || text.contains("camera") || text.contains("caméra") || text.contains("photo")) {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return if (intent.resolveActivity(context.packageManager) != null) {
                CommandResult.Handled(
                    responseSpeech = "J'ouvre l'appareil photo.",
                    actionDescription = "Ouverture de l'appareil photo",
                    intentToLaunch = intent
                )
            } else {
                CommandResult.Handled(
                    responseSpeech = "Aucune application d'appareil photo n'est accessible.",
                    actionDescription = "Appareil photo non accessible"
                )
            }
        }

        // 10. Ouvrir la galerie
        if (text.contains("galerie") || text.contains("photos")) {
            val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return CommandResult.Handled(
                responseSpeech = "J'ouvre la galerie de photos.",
                actionDescription = "Ouverture de la Galerie",
                intentToLaunch = intent
            )
        }

        // 11. Ouvrir le navigateur
        if (text.contains("navigateur") || text.contains("internet") || text.contains("google")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return CommandResult.Handled(
                responseSpeech = "J'ouvre votre navigateur Internet.",
                actionDescription = "Lancement du navigateur",
                intentToLaunch = intent
            )
        }

        // 12. Téléphone / Clavier
        if (text.contains("téléphone") || text.contains("telephone") || text.contains("appel") || text.contains("clavier")) {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return CommandResult.Handled(
                responseSpeech = "J'ouvre le clavier téléphonique.",
                actionDescription = "Ouverture du composeur d'appels",
                intentToLaunch = intent
            )
        }

        // 13. Qui es-tu ? / Identité
        if (text.contains("qui es-tu") || text.contains("qui es tu") || text.contains("t'appelles") || text.contains("ton nom") || text.contains("présente-toi") || text.contains("presente toi")) {
            return CommandResult.Handled(
                responseSpeech = "Je suis DJAMI, votre assistante vocale et intelligence artificielle intelligente en français pour Android.",
                actionDescription = "Présentation de DJAMI"
            )
        }

        // 14. Blague
        if (text.contains("blague") || text.contains("drôle") || text.contains("fais-moi rire")) {
            val blague = jokes.random()
            return CommandResult.Handled(
                responseSpeech = blague,
                actionDescription = "Raconter une blague"
            )
        }

        // 15. Citation
        if (text.contains("citation") || text.contains("proverbe") || text.contains("inspire")) {
            val citation = quotes.random()
            return CommandResult.Handled(
                responseSpeech = citation,
                actionDescription = "Citation inspirante"
            )
        }

        // 16. Calcul simple
        val calcResponse = tryComputeSimpleMath(text)
        if (calcResponse != null) {
            return CommandResult.Handled(
                responseSpeech = calcResponse,
                actionDescription = "Calcul mathématique local"
            )
        }

        return CommandResult.NotHandled
    }

    private fun tryComputeSimpleMath(text: String): String? {
        val clean = text.replace("combien fait", "").replace("calcule", "").trim()
        val regex = Regex("(\\d+)\\s*([+\\-*/xX]|plus|moins|fois|divisé par)\\s*(\\d+)")
        val match = regex.find(clean) ?: return null
        val (aStr, opStr, bStr) = match.destructured
        val a = aStr.toLongOrNull() ?: return null
        val b = bStr.toLongOrNull() ?: return null

        return when {
            opStr in listOf("+", "plus") -> "$a plus $b égale ${a + b}."
            opStr in listOf("-", "moins") -> "$a moins $b égale ${a - b}."
            opStr in listOf("*", "x", "X", "fois") -> "$a fois $b égale ${a * b}."
            opStr in listOf("/", "divisé par") -> {
                if (b == 0L) "La division par zéro est impossible."
                else "$a divisé par $b égale ${a.toDouble() / b.toDouble()}."
            }
            else -> null
        }
    }
}
