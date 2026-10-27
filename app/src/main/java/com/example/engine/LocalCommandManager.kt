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
            .replace("s'il te plait", "")
            .replace("s'il vous plait", "")
            .trim()

        // 1. Commande d'arrêt vocal
        if (text == "stop" || text.contains("arrête de parler") || text.contains("arrete de parler") || text.contains("tais-toi") || text == "silence") {
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
        if (text.contains("active la veille") || text.contains("allume la veille") || text.contains("active l'écoute en fond") || text.contains("active l ecoute en fond")) {
            return CommandResult.Handled(
                responseSpeech = "Activation de la veille vocale.",
                actionDescription = "Veille vocale activée",
                toggleWakeWord = true
            )
        }
        if (text.contains("désactive la veille") || text.contains("desactive la veille") || text.contains("arrête la veille") || text.contains("arrete la veille") || text.contains("stoppe la veille")) {
            return CommandResult.Handled(
                responseSpeech = "Veille vocale désactivée.",
                actionDescription = "Veille vocale désactivée",
                toggleWakeWord = false
            )
        }

        // Détection stricte des demandes de l'heure courante (ne doit pas intercepter "à quelle heure...", les horaires ou "décalage horaire")
        val isScheduledTimeQuestion = text.startsWith("à quelle heure") || text.startsWith("a quelle heure") ||
                text.contains("à quelle heure") || text.contains("a quelle heure")
        val asksCurrentTime = !isScheduledTimeQuestion && (
                text == "heure" || text == "l'heure" || text == "quelle heure" ||
                text.contains("quelle heure est-il") || text.contains("quelle heure est il") ||
                text.contains("heure actuelle") || text.contains("donne-moi l'heure") ||
                text.contains("donne moi l'heure") || text.contains("dis-moi l'heure") ||
                text.contains("dis moi l'heure") || text.contains("il est quelle heure") ||
                text.contains("on est à quelle heure")
        )

        // 4. Heure
        if (asksCurrentTime) {
            val format = SimpleDateFormat("HH'h'mm", Locale.FRENCH)
            val heure = format.format(Date()).replace("h00", " heures précises").replace("h", " heures ")
            return CommandResult.Handled(
                responseSpeech = "Il est $heure.",
                actionDescription = "Consultation de l'heure"
            )
        }

        // Détection stricte de la date du jour (évite d'intercepter "date de la fête", "date d'anniversaire", etc.)
        val isSpecificOrHistoricalDate = text.contains("date de") || text.contains("date du match") ||
                text.contains("date d'anniversaire") || text.contains("date limite") || text.contains("date d'expiration")
        val asksCurrentDate = !isSpecificOrHistoricalDate && (
                text == "date" || text == "quelle est la date" || text.startsWith("quelle est la date d'aujourd'hui") ||
                text.startsWith("quelle est la date aujourd'hui") || text.startsWith("quelle est la date du jour") ||
                text.contains("on est quel jour") || text.contains("quel jour sommes-nous") ||
                text.contains("quel jour sommes nous") || text.contains("date d'aujourd'hui") ||
                text.contains("date aujourd'hui") || text == "donne-moi la date" ||
                text == "donne moi la date" || text == "dis-moi la date" || text == "dis moi la date"
        )

        // 5. Date
        if (asksCurrentDate) {
            val format = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH)
            val dateFormatee = format.format(Date())
            return CommandResult.Handled(
                responseSpeech = "Aujourd'hui, nous sommes le $dateFormatee.",
                actionDescription = "Consultation de la date"
            )
        }

        // Seules les demandes explicites d'ouverture doivent lancer une application.
        // Les questions d'explication (ex: "explique-moi WhatsApp", "c'est quoi YouTube", "qui a créé WhatsApp")
        // NE DOIVENT PAS ouvrir l'application mais être transmises à l'IA.
        val isExplanationOrQuestion = text.startsWith("explique") ||
                text.startsWith("pourquoi") ||
                text.startsWith("c'est quoi") ||
                text.startsWith("qui a") ||
                text.startsWith("comment marche") ||
                text.startsWith("comment fonctionne") ||
                text.startsWith("parle-moi de") ||
                text.startsWith("parle moi de") ||
                text.startsWith("qu'est-ce que") ||
                text.startsWith("qu est ce que")

        val asksToOpen = !isExplanationOrQuestion && (
                Regex("^(?:s'il te plait\\s+|s'il vous plait\\s+)?(?:ouvre|ouvrir|lance|lancer|demarre|démarre|va sur|aller sur|affiche|mets|peux-tu ouvrir|peux tu ouvrir|peux-tu m'ouvrir|peux tu m'ouvrir)\\b")
                    .containsMatchIn(text) || text in setOf("whatsapp", "youtube", "google", "internet", "navigateur", "appareil photo", "camera", "caméra", "galerie", "paramètres", "parametres")
        )

        // 6. Ouvrir WhatsApp
        if (text.contains("whatsapp") && asksToOpen) {
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
        if (text.contains("youtube") && asksToOpen) {
            val pkg = "com.google.android.youtube"
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            return if (launchIntent != null) {
                CommandResult.Handled(
                    responseSpeech = "J'ouvre YouTube.",
                    actionDescription = "Lancement de YouTube",
                    intentToLaunch = launchIntent
                )
            } else {
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
        if ((text.contains("paramètre") || text.contains("parametre") || text.contains("configuration") || text.contains("réglage") || text.contains("reglage")) && asksToOpen) {
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
        if ((text.contains("appareil photo") || text.contains("camera") || text.contains("caméra") || text == "photo") && asksToOpen) {
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
        if ((text.contains("galerie") || text == "photos" || text.contains("mes photos")) && asksToOpen) {
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
        if ((text.contains("navigateur") || text.contains("internet") || text.contains("google")) && asksToOpen) {
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
        if ((text.contains("ouvre le clavier") || text.contains("ouvre le téléphone") ||
                text.contains("ouvre le telephone") || text.contains("clavier téléphonique") ||
                text.contains("clavier telephonique") || text.startsWith("appelle ")) && (asksToOpen || text.startsWith("appelle "))) {
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
                responseSpeech = "Je suis DJAMI, votre assistante vocale et intelligence artificielle en français pour Android.",
                actionDescription = "Présentation de DJAMI"
            )
        }

        // 14. Blague (demande explicite)
        if ((text.contains("blague") || text.contains("drôle") || text.contains("drole") || text.contains("fais-moi rire") || text.contains("fais moi rire")) &&
            (text.contains("raconte") || text.contains("dis-moi") || text.contains("dis moi") || text.contains("fais-moi rire") || text.contains("fais moi rire") || text.startsWith("une blague") || text == "blague")) {
            val blague = jokes.random()
            return CommandResult.Handled(
                responseSpeech = blague,
                actionDescription = "Raconter une blague"
            )
        }

        // 15. Citation inspirante
        if ((text.contains("citation") || text.contains("proverbe")) &&
            (text.contains("donne") || text.contains("dis-moi") || text.contains("dis moi") || text.contains("raconte") || text.startsWith("une citation") || text.startsWith("un proverbe") || text == "citation" || text == "proverbe")) {
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
