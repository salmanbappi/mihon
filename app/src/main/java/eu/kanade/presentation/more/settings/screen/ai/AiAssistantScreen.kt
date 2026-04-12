package eu.kanade.presentation.more.settings.screen.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.MarkdownRender
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.more.settings.screen.ai.AiAssistantScreenModel
import eu.kanade.tachiyomi.ui.more.settings.screen.ai.AiMessage
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

class AiAssistantScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AiAssistantScreenModel() }
        val state by screenModel.state.collectAsState()
        val sessions by screenModel.sessions.collectAsState()
        
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var input by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(state.messages.size, state.isLoading, state.streamingMessage) {
            if (state.messages.isNotEmpty() || state.streamingMessage != null) {
                val lastIndex = if (state.streamingMessage != null) state.messages.size else state.messages.size - 1
                if (lastIndex >= 0) {
                    listState.animateScrollToItem(lastIndex)
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "DIAGNOSTIC SESSIONS",
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Add, null) },
                        label = { Text("New Diagnosis") },
                        selected = false,
                        onClick = {
                            screenModel.createNewSession()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                    
                    HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp))
                    
                    LazyColumn {
                        items(sessions) { session ->
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.History, null) },
                                label = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                selected = state.activeSessionId == session.id,
                                onClick = {
                                    screenModel.switchSession(session.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                        }
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    AppBar(
                        title = "Mihon AI Diagnosis",
                        navigateUp = { navigator.pop() },
                        actions = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "History")
                            }
                        }
                    )
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (state.messages.isEmpty() && state.streamingMessage == null) {
                            item {
                                MessageBubble(
                                    message = AiMessage("assistant", "System online. I can analyze your library and logs to diagnose issues or provide insights. How can I help?"),
                                    onCopy = { context.copyToClipboard("Mihon AI", it) }
                                )
                            }
                        }

                        items(state.messages) { msg ->
                            MessageBubble(message = msg, onCopy = { context.copyToClipboard("Mihon AI", it) })
                        }

                        if (state.streamingMessage != null) {
                            item {
                                MessageBubble(
                                    message = AiMessage("assistant", state.streamingMessage!!),
                                    onCopy = { context.copyToClipboard("Mihon AI", it) }
                                )
                            }
                        }
                    }

                    ChatInput(
                        value = input,
                        onValueChange = { input = it },
                        onSend = {
                            screenModel.sendMessage(input)
                            input = ""
                        },
                        isLoading = state.isLoading
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AiMessage, onCopy: (String) -> Unit) {
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isUser) {
                    MarkdownRender(content = message.content)
                } else {
                    Text(text = message.content, color = MaterialTheme.colorScheme.onPrimary)
                }
                
                if (!isUser) {
                    IconButton(
                        onClick = { onCopy(message.content) },
                        modifier = Modifier.align(Alignment.End).size(24.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Ask about system health...") },
                modifier = Modifier.weight(1f),
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (value.isNotBlank() && !isLoading) MaterialTheme.colorScheme.primary 
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    ),
                enabled = value.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp), 
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, 
                        null, 
                        tint = if (value.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
