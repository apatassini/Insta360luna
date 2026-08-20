package it.persoft.lunaultra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.ui.screens.ControlScreen
import it.persoft.lunaultra.ui.screens.DiagnosticsScreen
import it.persoft.lunaultra.ui.screens.SequenceScreen

private val TABS = listOf("Controllo", "Sequenza", "Diagnostica")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LunaApp(viewModel: MainViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsState()
    val connection by viewModel.connectionState.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Luna Timelapse") },
                actions = {
                    Text(
                        text = connection.italianLabel(),
                        color = when (connection) {
                            ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                            ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }
            when (selectedTab) {
                0 -> ControlScreen(viewModel)
                1 -> SequenceScreen(viewModel)
                else -> DiagnosticsScreen(viewModel)
            }
        }
    }
}

fun ConnectionState.italianLabel(): String = when (this) {
    ConnectionState.DISCONNECTED -> "Disconnesso"
    ConnectionState.CONNECTING -> "Connessione…"
    ConnectionState.HANDSHAKE -> "Handshake…"
    ConnectionState.CONNECTED -> "Connesso"
    ConnectionState.ERROR -> "Errore"
}
