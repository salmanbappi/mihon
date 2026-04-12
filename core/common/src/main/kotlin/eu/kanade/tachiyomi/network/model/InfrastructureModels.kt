package eu.kanade.tachiyomi.network.model

data class InfrastructureReport(
    val nodes: List<SourceNode>,
    val globalMetrics: GlobalNetworkMetrics,
    val systemLogs: List<SystemLogEntry>,
)

data class SourceNode(
    val name: String,
    val pkgName: String,
    val version: String,
    val status: NodeStatus,
    val network: NetworkDiagnostics,
    val capabilities: SourceCapabilities,
    val uptimeScore: Double,
)

data class NetworkDiagnostics(
    val latency: Int,
    val topology: String,
    val ipAddress: String,
    val tlsVersion: String,
    val dnsResolved: Boolean,
)

data class SourceCapabilities(
    val isApi: Boolean,
    val mtSupport: Boolean,
    val latestSupport: Boolean,
    val searchSupport: Boolean,
)

data class GlobalNetworkMetrics(
    val totalNodes: Int,
    val activeNodeCount: Int,
    val avgLatency: Int,
    val totalDataConsumed: Long = 0,
)

data class SystemLogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val source: String,
    val message: String,
)

enum class NodeStatus {
    OPERATIONAL, DEGRADED, OFFLINE, MAINTENANCE
}

enum class LogLevel {
    INFO, WARN, ERROR
}
