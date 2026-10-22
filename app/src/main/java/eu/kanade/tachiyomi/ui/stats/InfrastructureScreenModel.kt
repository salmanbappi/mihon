package eu.kanade.tachiyomi.ui.stats

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceHealthCache
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class InfrastructureScreenModel(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val networkHelper: NetworkHelper,
    private val libraryPreferences: LibraryPreferences,
) : ViewModel() {

    val state: StateFlow<InfrastructureState>
        field = MutableStateFlow<InfrastructureState>(InfrastructureState.Loading)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    private val semaphore = Semaphore(5)

    init {
        runDiagnostics()
    }

    fun copyReportToClipboard() {
        val state = state.value
        if (state !is InfrastructureState.Success) return

        val report = state.report
        val sb = StringBuilder()
        sb.append("--- MIHON EXTENSION HEALTH REPORT ---\n")
        sb.append("Timestamp: ${java.time.Instant.now()}\n")
        sb.append("Avg Latency: ${report.globalMetrics.avgLatency}ms\n")
        sb.append("Active Nodes: ${report.globalMetrics.activeNodeCount}/${report.nodes.size}\n\n")

        sb.append("--- NODE STATUS ---\n")
        report.nodes.forEach { node ->
            sb.append("${node.name} [${node.status}]: ${node.network.latency}ms (${node.network.topology})\n")
            sb.append("  IP: ${node.network.ipAddress}, TLS: ${node.network.tlsVersion}\n")
        }

        sb.append("\n--- SYSTEM LOGS ---\n")
        report.systemLogs.forEach { log ->
            sb.append("[${log.level.name}] ${log.source}: ${log.message}\n")
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Mihon Extension Health Report", sb.toString())
        clipboard.setPrimaryClip(clip)

        viewModelScope.launchIO {
            _events.send(Event.ReportCopied)
        }
    }

    fun runDiagnostics() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        
        viewModelScope.launchIO {
            val sources = sourceManager.getOnlineSources()
                .filter { !it.isLocal() }
            
            val initialNodes = sources.map { source ->
                createPlaceholderNode(source)
            }
            
            state.update { 
                InfrastructureState.Success(InfrastructureReport(initialNodes, generateEmptyMetrics(initialNodes.size), emptyList()))
            }

            val nodes = sources.map { source ->
                async {
                    semaphore.withPermit {
                        probeNode(source).also { finishedNode ->
                            updateNodeInState(finishedNode)
                            SourceHealthCache.updateStatus(source.id, finishedNode.status, finishedNode.network.latency)
                        }
                    }
                }
            }.awaitAll()

            val sortedNodes = nodes.sortedWith(compareByDescending<SourceNode> { it.status == NodeStatus.OPERATIONAL }
                .thenBy { it.network.latency })

            val logs = nodes.filter { it.status != NodeStatus.OPERATIONAL }.map { node ->
                SystemLogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = if (node.status == NodeStatus.OFFLINE) LogLevel.ERROR else LogLevel.WARN,
                    source = node.name,
                    message = "Alert: ${node.status}. Response: ${node.network.latency}ms"
                )
            }

            val metrics = GlobalNetworkMetrics(
                totalNodes = nodes.size,
                activeNodeCount = nodes.count { it.status == NodeStatus.OPERATIONAL },
                avgLatency = if (nodes.isNotEmpty()) nodes.filter { it.status == NodeStatus.OPERATIONAL }.map { it.network.latency }.average().toInt() else 0,
            )

            state.update {
                InfrastructureState.Success(InfrastructureReport(sortedNodes, metrics, logs))
            }
            _isRefreshing.value = false
        }
    }

    private fun createPlaceholderNode(source: HttpSource): SourceNode {
        return SourceNode(
            name = source.name,
            pkgName = source::class.java.name.substringBeforeLast("."),
            version = "Scanning...",
            status = NodeStatus.OPERATIONAL,
            network = NetworkDiagnostics(0, "Global", "...", "...", false),
            capabilities = SourceCapabilities(detectIsApi(source), false, source.supportsLatest, true),
            uptimeScore = 1.0
        )
    }

    private fun updateNodeInState(node: SourceNode) {
        state.update { state ->
            if (state is InfrastructureState.Success) {
                val updatedNodes = state.report.nodes.map { if (it.name == node.name) node else it }
                state.copy(report = state.report.copy(nodes = updatedNodes))
            } else state
        }
    }

    private fun generateEmptyMetrics(count: Int) = GlobalNetworkMetrics(count, count, 0)

    private fun detectIsApi(source: HttpSource): Boolean {
        val name = source.name.lowercase()
        val className = source::class.java.simpleName.lowercase()
        val pkg = source::class.java.name.lowercase()
        
        return className.contains("api") || 
               className.contains("json") || 
               className.contains("graphql") ||
               name.contains("api") || 
               name.contains("json") ||
               pkg.contains("api") ||
               pkg.contains("json")
    }

    private suspend fun probeNode(source: HttpSource): SourceNode {
        var latency = 999
        var resolved = false
        var ip = "Scanning"
        var tls = "Unknown"
        var status = NodeStatus.OFFLINE

        try {
            val probeClient = networkHelper.client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            val request = Request.Builder()
                .url(source.baseUrl)
                .headers(source.headers)
                .header("X-Mihon-Probe", "ExtensionHealth-v1.0")
                .build()

            measureTimeMillis {
                probeClient.newCall(request).execute().use { response ->
                    resolved = true
                    tls = response.handshake?.tlsVersion?.javaName ?: "v1.3"
                    if (response.code in 200..499) {
                        status = NodeStatus.OPERATIONAL
                    } else if (response.code >= 500) {
                        status = NodeStatus.DEGRADED
                    } else {
                        status = NodeStatus.OPERATIONAL
                    }
                }
            }.let { latency = it.toInt() }

            val domain = source.baseUrl.substringAfter("://").substringBefore("/")
            ip = try { InetAddress.getByName(domain).hostAddress ?: "0.0.0.0" } catch(e: Exception) { "DNS Fail" }

        } catch (e: Exception) {
            status = NodeStatus.OFFLINE
            ip = "Network Err"
        }

        return SourceNode(
            name = source.name,
            pkgName = source::class.java.name.substringBeforeLast("."),
            version = "PROBED",
            status = if (status == NodeStatus.OPERATIONAL && latency > 2500) NodeStatus.DEGRADED else status,
            network = NetworkDiagnostics(
                latency = latency,
                topology = "Global CDN",
                ipAddress = ip,
                tlsVersion = tls,
                dnsResolved = resolved
            ),
            capabilities = SourceCapabilities(
                isApi = detectIsApi(source),
                mtSupport = false,
                latestSupport = source.supportsLatest,
                searchSupport = true
            ),
            uptimeScore = if (status == NodeStatus.OPERATIONAL) 1.0 else 0.0
        )
    }

    sealed interface Event {
        data object ReportCopied : Event
    }
}

sealed interface InfrastructureState {
    data object Loading : InfrastructureState
    data class Success(val report: InfrastructureReport) : InfrastructureState
}
