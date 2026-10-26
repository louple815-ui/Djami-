package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.service.WakeWordService
import com.example.ui.DjamiScreen
import com.example.ui.DjamiViewModel
import com.example.ui.theme.DjamiBackground
import com.example.ui.theme.DjamiTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DjamiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleWakeIntent(intent)

        setContent {
            DjamiTheme {
                val uiState by viewModel.uiState.collectAsState()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DjamiBackground
                ) {
                    DjamiScreen(
                        viewModel = viewModel,
                        uiState = uiState
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(WakeWordService.EXTRA_WAKE_TRIGGERED, false) == true) {
            val text = intent.getStringExtra(WakeWordService.EXTRA_WAKE_TEXT) ?: ""
            viewModel.logDiagnostic("Application réveillée par le mot-clé Hey DJAMI ($text)")
            // Déclenche l'écoute active pour la commande suivante
            viewModel.startListening()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopListening()
    }
}
