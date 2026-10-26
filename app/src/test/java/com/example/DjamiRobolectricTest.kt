package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.CommandResult
import com.example.engine.LocalCommandManager
import com.example.engine.OfflineAiEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DjamiRobolectricTest {

    private lateinit var context: Context
    private lateinit var commandManager: LocalCommandManager
    private lateinit var offlineAi: OfflineAiEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        commandManager = LocalCommandManager(context)
        offlineAi = OfflineAiEngine()
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
}
