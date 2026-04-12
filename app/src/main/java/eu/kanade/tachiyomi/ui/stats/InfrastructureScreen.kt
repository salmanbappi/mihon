package eu.kanade.tachiyomi.ui.stats

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.presentation.core.components.material.Scaffold
import kotlinx.coroutines.flow.collectLatest

object InfrastructureScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { InfrastructureScreenModel() }
        val state by screenModel.state.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    InfrastructureScreenModel.Event.ReportCopied -> {
                        // Toast or Snackbar could be added here
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = "Extension Health",
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = "Copy Report",
                                    icon = Icons.Outlined.ContentCopy,
                                    onClick = screenModel::copyReportToClipboard,
                                ),
                            ),
                        )
                    },
                )
            },
        ) { contentPadding ->
            eu.kanade.presentation.more.stats.InfrastructureScreen(
                state = state,
                isRefreshing = isRefreshing,
                onRefresh = screenModel::runDiagnostics,
                contentPadding = contentPadding,
            )
        }
    }
}
