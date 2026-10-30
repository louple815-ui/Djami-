package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.CommandResult
import com.example.engine.HybridAiEngine
import com.example.engine.LocalCommandManager
import com.example.engine.OfflineAiEngine
import com.example.speech.AudioCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DjamiRobolectricTest {

    private lateinit var context: Context
    private lateinit var commandManager: LocalCommandManager
    private lateinit var offlineAi: OfflineAiEngine
    private lateinit var hybridAi: HybridAiEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        commandManager = LocalCommandManager(context)
        offlineAi = OfflineAiEngine()
        hybridAi = HybridAiEngine(context)
    }

    @Test
    fun testAppNameIsDjami() {
        val appName = context.getString(R.string.app_name)
        assertEquals("DJAMI", appName)
    }

    @Test
    fun testLocalCommandHeure() {
        val res = commandManager.processCommand("DJAMI, quelle heure est-il ?")
        assertTrue("La commande heure doit être traitée", res is CommandResult.Handled)
        val handled = res as CommandResult.Handled
        assertTrue("La réponse doit contenir 'Il est'", handled.responseSpeech.contains("Il est"))
    }

    @Test
    fun testLocalCommandDate() {
        val res = commandManager.processCommand("DJAMI, quelle est la date aujourd'hui ?")
        assertTrue("La commande date doit être traitée", res is CommandResult.Handled)
        val handled = res as CommandResult.Handled
        assertTrue("La réponse doit contenir 'nous sommes'", handled.responseSpeech.contains("nous sommes"))
    }

    @Test
    fun testDisambiguationTimeAndDate() {
        // Une question générale qui mentionne simplement une heure ne doit PAS être interceptée par l'heure courante
        val resTrain = commandManager.processCommand("à quelle heure part le train de Ouagadougou ?")
        assertTrue("Ne doit pas être intercepté comme l'heure courante", resTrain is CommandResult.NotHandled)

        val resDateHistory = commandManager.processCommand("quelle est la date de la fête de l'indépendance ?")
        assertTrue("Ne doit pas être intercepté comme la date du jour", resDateHistory is CommandResult.NotHandled)
    }

    @Test
    fun testDisambiguationAppExplanationsNotOpeningApp() {
        // 'Explique-moi à quoi sert WhatsApp' ne doit JAMAIS tenter d'ouvrir l'application
        val resWhatsapp = commandManager.processCommand("explique-moi à quoi sert WhatsApp")
        assertTrue("Doit être transmis à l'IA", resWhatsapp is CommandResult.NotHandled)

        val resCreator = commandManager.processCommand("qui a créé WhatsApp ?")
        assertTrue("Doit être transmis à l'IA", resCreator is CommandResult.NotHandled)

        val resYoutube = commandManager.processCommand("c'est quoi YouTube ?")
        assertTrue("Doit être transmis à l'IA", resYoutube is CommandResult.NotHandled)

        val resCamera = commandManager.processCommand("parle-moi de l'appareil photo")
        assertTrue("Doit être transmis à l'IA", resCamera is CommandResult.NotHandled)
    }

    @Test
    fun testExplicitAppOpeningCommands() {
        val resWhatsapp = commandManager.processCommand("ouvre WhatsApp")
        assertTrue("Doit traiter la commande d'ouverture WhatsApp", resWhatsapp is CommandResult.Handled)

        val resYoutube = commandManager.processCommand("lance YouTube")
        assertTrue("Doit traiter la commande d'ouverture YouTube", resYoutube is CommandResult.Handled)

        val resSettings = commandManager.processCommand("ouvre les paramètres")
        assertTrue("Doit traiter la commande paramètres", resSettings is CommandResult.Handled)
    }

    @Test
    fun testLocalCommandStop() {
        val res = commandManager.processCommand("arrête de parler")
        assertTrue(res is CommandResult.Handled)
        val handled = res as CommandResult.Handled
        assertTrue("isStopSpeech doit être true", handled.isStopSpeech)
    }

    @Test
    fun testLocalCommandMath() {
        val res = commandManager.processCommand("calcule 25 plus 17")
        assertTrue(res is CommandResult.Handled)
        val handled = res as CommandResult.Handled
        assertTrue("Le calcul doit donner 42", handled.responseSpeech.contains("42"))
    }

    @Test
    fun testLocalCommandIdentity() {
        val res = commandManager.processCommand("qui es-tu ?")
        assertTrue(res is CommandResult.Handled)
        val handled = res as CommandResult.Handled
        assertTrue("Doit mentionner DJAMI", handled.responseSpeech.contains("DJAMI"))
    }

    @Test
    fun testOfflineAiGreetings() = runBlocking {
        val res = offlineAi.generateResponse("Bonjour comment vas-tu ?")
        assertNotNull(res)
        assertTrue("La réponse doit être chaleureuse", res.contains("Bonjour") || res.contains("bien"))
    }

    @Test
    fun testOfflineAiHonestLimitation() = runBlocking {
        val res = offlineAi.generateResponse("Explique-moi la théorie de la relativité générale")
        assertNotNull(res)
        assertTrue("Doit expliquer honnêtement les limites du mode hors-ligne", res.contains("hors-ligne") || res.contains("limité"))
    }

    @Test
    fun testHybridAiEngineForceOffline() = runBlocking {
        val res = hybridAi.generateResponse("Bonjour", forceOffline = true)
        assertTrue("Doit répondre avec le moteur hors ligne", res.contains("Bonjour") || res.contains("DJAMI"))
    }

    @Test
    fun testHybridAiEngineMissingKeyGuidance() = runBlocking {
        // En mode en ligne, si la clé est le placeholder par défaut, renvoie une consigne claire pour AI Studio Secrets
        val res = hybridAi.generateResponse("Quelle est la capitale du Burkina Faso ?", forceOffline = false)
        assertTrue("Doit guider l'utilisateur pour renseigner GEMINI_API_KEY dans AI Studio",
            res.contains("GEMINI_API_KEY") || res.contains("Secrets") || res.contains("connexion Internet"))
    }

    @Test
    fun testAudioCoordinatorManualListening() {
        var ttsStopped = false
        AudioCoordinator.registerTtsControls { ttsStopped = true }

        AudioCoordinator.onTtsStarted("Test de parole")
        assertEquals(AudioCoordinator.AudioMode.SPEAKING, AudioCoordinator.currentMode.value)

        AudioCoordinator.onRequestManualListening()
        assertTrue("Le TTS doit être stoppé avant écoute manuelle", ttsStopped)
        assertEquals(AudioCoordinator.AudioMode.MANUAL_LISTENING, AudioCoordinator.currentMode.value)

        AudioCoordinator.onManualListeningEnded()
        assertEquals(AudioCoordinator.AudioMode.IDLE, AudioCoordinator.currentMode.value)
    }

    @Test
    fun testAudioCoordinatorAntiEchoFilter() {
        AudioCoordinator.onTtsStarted("Il est actuellement quatorze heures.")
        AudioCoordinator.onTtsFinished()

        val isEcho = AudioCoordinator.isSelfVoiceEcho("Il est actuellement quatorze heures.")
        assertTrue("L'écho de DJAMI doit être filtré", isEcho)

        val isNotEcho = AudioCoordinator.isSelfVoiceEcho("Quelle est la météo à Dakar ?")
        assertFalse("Une question utilisateur légitime ne doit pas être filtrée", isNotEcho)
    }

    @Test
    fun testAudioCoordinatorWakeWordSync() {
        AudioCoordinator.setWakeWordEnabled(false)
        assertFalse(AudioCoordinator.isWakeWordEnabled.value)

        AudioCoordinator.setWakeWordEnabled(true)
        assertTrue(AudioCoordinator.isWakeWordEnabled.value)

        AudioCoordinator.setWakeWordEnabled(false)
        assertFalse(AudioCoordinator.isWakeWordEnabled.value)
    }

    @Test
    fun testWakeWordDetectionRegex() {
        fun isWakeWordDetected(text: String): Boolean {
            val normalized = java.text.Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            return normalized.matches(Regex(".*\\b(hey|he|eh|ok|okay|bonjour)\\s+(djami|jami|dami)\\b.*")) ||
                normalized.matches(Regex("(djami|jami|dami)"))
        }

        assertTrue("Hey DJAMI doit réveiller", isWakeWordDetected("Hey DJAMI"))
        assertTrue("Hé Djami avec accent doit réveiller", isWakeWordDetected("Hé Djami"))
        assertTrue("Bonjour djami doit réveiller", isWakeWordDetected("Bonjour DJAMI"))
        assertTrue("Ok djami doit réveiller", isWakeWordDetected("Ok DJAMI"))
        assertTrue("DJAMI seul doit réveiller", isWakeWordDetected("DJAMI"))
        assertFalse("Un mot sans réveil ne doit pas réveiller", isWakeWordDetected("la météo du jour"))
    }
}
