package eu.kanade.tachiyomi.ui.stats

import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.core.util.fastCountNot
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.data.ExtensionInfo
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.tachiyomi.data.ai.AiManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.source.local.isLocal
import java.util.Calendar

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StatsViewModel(
    private val downloadManager: DownloadManager,
    private val getLibraryManga: GetLibraryManga,
    private val getTotalReadDuration: GetTotalReadDuration,
    private val getTracks: GetTracks,
    private val getHistory: GetHistory,
    private val preferences: LibraryPreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val aiManager: AiManager,
    private val aiPreferences: AiPreferences,
) : ViewModel() {

    val state: StateFlow<StatsScreenState>
        field = MutableStateFlow<StatsScreenState>(StatsScreenState.Loading)

    private val loggedInTrackers by lazy { trackerManager.loggedInTrackers() }

    init {
        viewModelScope.launchIO {
            val libraryManga = getLibraryManga.await()
            val history = getHistory.subscribe("").first()

            val distinctLibraryManga = libraryManga.fastDistinctBy { it.id }

            val mangaTrackMap = getMangaTrackMap(distinctLibraryManga)
            val scoredMangaTrackerMap = getScoredMangaTrackMap(mangaTrackMap)

            val meanScore = getCombinedMeanScore(scoredMangaTrackerMap)

            val overviewStatData = StatsData.Overview(
                libraryMangaCount = distinctLibraryManga.size,
                completedMangaCount = distinctLibraryManga.count {
                    it.manga.status.toInt() == SManga.COMPLETED && it.unreadCount == 0L
                },
                totalReadDuration = getTotalReadDuration.await(),
            )

            val titlesStatData = StatsData.Titles(
                globalUpdateItemCount = getGlobalUpdateItemCount(libraryManga),
                startedMangaCount = distinctLibraryManga.count { it.hasStarted },
                localMangaCount = distinctLibraryManga.count { it.manga.isLocal() },
            )

            val chaptersStatData = StatsData.Chapters(
                totalChapterCount = distinctLibraryManga.sumOf { it.totalChapters }.toInt(),
                readChapterCount = distinctLibraryManga.sumOf { it.readCount }.toInt(),
                downloadCount = downloadManager.getDownloadCount(),
            )

            val trackersStatData = StatsData.Trackers(
                trackedTitleCount = mangaTrackMap.count { it.value.isNotEmpty() },
                meanScore = meanScore,
                trackerCount = loggedInTrackers.size,
            )

            // Extension Usage
            val installedExtensions = extensionManager.installedExtensionsFlow.first()
            val extensionUsage = StatsData.ExtensionUsage(
                topExtensions = distinctLibraryManga
                    .map { it.manga.source }
                    .groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }.take(5)
                    .map { entry ->
                        val source = sourceManager.getOrStub(entry.key)
                        val ext = installedExtensions.find { it.sources.any { s -> s.id == entry.key } }
                        val repoUrl = ext?.store?.indexUrl
                        val repoName = when {
                            repoUrl == null -> null
                            repoUrl.contains("github.com/") -> {
                                repoUrl.substringAfter("github.com/").substringBefore("/raw")
                            }
                            else -> repoUrl.substringAfter("://").substringBefore("/")
                        }

                        ExtensionInfo(
                            name = source.name,
                            count = entry.value,
                            repo = repoName
                        )
                    }
            )

            // Genre Affinity
            val genreAffinity = StatsData.GenreAffinity(
                genreScores = distinctLibraryManga.flatMap { it.manga.genre ?: emptyList() }
                    .groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }.take(10)
                    .map { it.toPair() }
            )

            // Time Distribution
            val timeDistribution = calculateTimeDistribution(history)

            // Read Habits
            val readHabits = calculateReadHabits(history, distinctLibraryManga)

            // Score Distribution
            val scoreDistribution = StatsData.ScoreDistribution(
                scoredMangaCount = scoredMangaTrackerMap.size,
                distribution = getCombinedScoreDistribution(scoredMangaTrackerMap)
            )

            // Status Breakdown
            val statusBreakdown = run {
                var completed = 0
                var ongoing = 0
                var dropped = 0
                var onHold = 0
                var planned = 0

                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)

                distinctLibraryManga.forEach { libraryManga ->
                    val tracks = mangaTrackMap[libraryManga.id] ?: emptyList()
                    
                    // Simple status parsing from tracks
                    val isDropped = tracks.any { it.status == 4L } // Typically DROPPED
                    val isOnHold = tracks.any { it.status == 3L } // Typically ON HOLD

                    val isStale = libraryManga.hasStarted && libraryManga.lastRead < thirtyDaysAgo && libraryManga.unreadCount > 0

                    when {
                        isDropped || (isStale && !libraryManga.manga.favorite) -> dropped++
                        isOnHold || (isStale && libraryManga.manga.favorite) -> onHold++
                        libraryManga.manga.status.toInt() == SManga.COMPLETED && libraryManga.unreadCount == 0L -> completed++
                        libraryManga.hasStarted -> ongoing++
                        else -> planned++
                    }
                }
                StatsData.StatusBreakdown(
                    completedCount = completed,
                    ongoingCount = ongoing,
                    droppedCount = dropped,
                    onHoldCount = onHold,
                    planToReadCount = planned,
                )
            }

            state.update {
                StatsScreenState.Success(
                    overview = overviewStatData,
                    titles = titlesStatData,
                    chapters = chaptersStatData,
                    trackers = trackersStatData,
                    extensions = extensionUsage,
                    timeDistribution = timeDistribution,
                    genreAffinity = genreAffinity,
                    readHabits = readHabits,
                    scores = scoreDistribution,
                    statuses = statusBreakdown,
                    aiAnalysis = aiPreferences.lastStatsAnalysis().get().takeIf { it.isNotBlank() },
                )
            }
        }
    }

    fun generateAiAnalysis() {
        val currentState = state.value
        if (currentState !is StatsScreenState.Success || currentState.aiAnalysis != null || currentState.isAiLoading) return
        startAiAnalysis(currentState)
    }

    fun regenerateAiAnalysis() {
        val currentState = state.value
        if (currentState !is StatsScreenState.Success || currentState.isAiLoading) return
        startAiAnalysis(currentState)
    }

    private fun startAiAnalysis(currentState: StatsScreenState.Success) {
        state.update {
            if (it is StatsScreenState.Success) it.copy(
                isAiLoading = true,
                streamingAnalysis = "",
                aiAnalysis = null
            ) else it
        }

        val summary = """
            Library Size: ${currentState.overview.libraryMangaCount}
            Completed: ${currentState.overview.completedMangaCount}
            Read Chapters: ${currentState.chapters.readChapterCount}
            Top Genres: ${currentState.genreAffinity.genreScores.joinToString { "${it.first} (${it.second})" }}
            Mean Score: ${currentState.trackers.meanScore}
            Read Habits: ${currentState.readHabits.preferredReadTime} cycle, ${currentState.readHabits.avgSessionsPerWeek} sessions/week
        """.trimIndent()

        viewModelScope.launchIO {
            val fullAnalysis = StringBuilder()
            try {
                aiManager.getStatisticsAnalysisStream(summary).collect { chunk ->
                    fullAnalysis.append(chunk)
                    state.update {
                        if (it is StatsScreenState.Success) it.copy(streamingAnalysis = fullAnalysis.toString()) else it
                    }
                }
                val finalResult = fullAnalysis.toString()
                if (finalResult.isNotBlank()) {
                    aiPreferences.lastStatsAnalysis().set(finalResult)
                    state.update {
                        if (it is StatsScreenState.Success) it.copy(
                            aiAnalysis = finalResult,
                            isAiLoading = false,
                            streamingAnalysis = null
                        ) else it
                    }
                }
            } catch (e: Exception) {
                state.update {
                    if (it is StatsScreenState.Success) it.copy(
                        isAiLoading = false,
                        streamingAnalysis = null
                    ) else it
                }
            }
        }
    }

    private fun calculateTimeDistribution(history: List<tachiyomi.domain.history.model.HistoryWithRelations>): StatsData.TimeDistribution {
        val daysDistribution = mutableMapOf<Int, Long>()
        val weeklyHeatmap = mutableMapOf<Int, Int>()

        history.forEach { item ->
            val cal = Calendar.getInstance().apply { time = item.readAt ?: return@forEach }
            val day = cal.get(Calendar.DAY_OF_WEEK)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            
            daysDistribution[day] = (daysDistribution[day] ?: 0L) + 1
            weeklyHeatmap[hour] = (weeklyHeatmap[hour] ?: 0) + 1
        }
        return StatsData.TimeDistribution(daysDistribution, weeklyHeatmap)
    }

    private fun calculateReadHabits(
        history: List<tachiyomi.domain.history.model.HistoryWithRelations>,
        mangaList: List<LibraryManga>
    ): StatsData.ReadHabits {
        val now = System.currentTimeMillis()
        val monthMillis = 30 * 24 * 60 * 60 * 1000L

        val recentHistory = history.filter { (it.readAt?.time ?: 0) > (now - monthMillis) }
        val sessionsByWeek = recentHistory.groupBy {
            val cal = Calendar.getInstance().apply { time = it.readAt!! }
            cal.get(Calendar.WEEK_OF_YEAR)
        }.size
        
        val avgSessions = if (sessionsByWeek > 0) recentHistory.size.toDouble() / 4.0 else 0.0

        val topDayManga = history.filter { (it.readAt?.time ?: 0) > (now - (24 * 60 * 60 * 1000L)) }
            .groupingBy { it.mangaId }.eachCount().maxByOrNull { it.value }
            ?.let { entry -> mangaList.find { it.id == entry.key }?.manga?.title }

        val topMonthManga = history.filter { (it.readAt?.time ?: 0) > (now - monthMillis) }
            .groupingBy { it.mangaId }.eachCount().maxByOrNull { it.value }
            ?.let { entry -> mangaList.find { it.id == entry.key }?.manga?.title }

        val hourCounts = history.mapNotNull { it.readAt }.map {
            Calendar.getInstance().apply { time = it }.get(Calendar.HOUR_OF_DAY)
        }.groupingBy { it }.eachCount()
        
        val topHour = hourCounts.maxByOrNull { it.value }?.key ?: 0
        val preferredTime = when (topHour) {
            in 5..11 -> "Morning"
            in 12..17 -> "Afternoon"
            in 18..22 -> "Evening"
            else -> "Late Night"
        }

        return StatsData.ReadHabits(topDayManga, topMonthManga, preferredTime, avgSessions)
    }

    private fun getGlobalUpdateItemCount(libraryManga: List<LibraryManga>): Int {
        val includedCategories = preferences.updateCategories.get().map { it.toLong() }
        val includedManga = if (includedCategories.isNotEmpty()) {
            libraryManga.filter { manga -> manga.categories.any { it in includedCategories } }
        } else {
            libraryManga
        }

        val excludedCategories = preferences.updateCategoriesExclude.get().map { it.toLong() }
        val excludedMangaIds = if (excludedCategories.isNotEmpty()) {
            libraryManga.mapNotNull { manga ->
                manga.id.takeIf { manga.categories.any { it in excludedCategories } }
            }
        } else {
            emptyList()
        }

        val updateRestrictions = preferences.autoUpdateMangaRestrictions.get()
        return includedManga
            .fastFilter { it.id !in excludedMangaIds }
            .fastDistinctBy { it.id }
            .fastCountNot {
                (MANGA_NON_COMPLETED in updateRestrictions && it.manga.status.toInt() == SManga.COMPLETED) ||
                    (MANGA_HAS_UNREAD in updateRestrictions && it.unreadCount != 0L) ||
                    (MANGA_NON_READ in updateRestrictions && it.totalChapters > 0 && !it.hasStarted)
            }
    }

    private suspend fun getMangaTrackMap(libraryManga: List<LibraryManga>): Map<Long, List<Track>> {
        val loggedInTrackerIds = loggedInTrackers.map { it.id }.toHashSet()
        return libraryManga.associate { manga ->
            val tracks = getTracks.await(manga.id)
                .fastFilter { it.trackerId in loggedInTrackerIds }

            manga.id to tracks
        }
    }

    private fun getScoredMangaTrackMap(mangaTrackMap: Map<Long, List<Track>>): Map<Long, List<Track>> {
        return mangaTrackMap.mapNotNull { (mangaId, tracks) ->
            val trackList = tracks.mapNotNull { track ->
                track.takeIf { it.score > 0.0 }
            }
            if (trackList.isEmpty()) return@mapNotNull null
            mangaId to trackList
        }.toMap()
    }

    private fun getCombinedMeanScore(
        scoredTrackMap: Map<Long, List<Track>>
    ): Double {
        val scores = scoredTrackMap.values.flatMap { tracks ->
            tracks.map { get10PointScore(it) }
        }
        
        return if (scores.isEmpty()) 0.0 else scores.average()
    }

    private fun getCombinedScoreDistribution(
        scoredTrackMap: Map<Long, List<Track>>
    ): Map<Int, Int> {
        val distribution = mutableMapOf<Int, Int>()
        
        scoredTrackMap.values.forEach { tracks ->
            val avgScore = tracks.map { get10PointScore(it) }.average()
            val scoreInt = avgScore.toInt().coerceIn(1, 10)
            distribution[scoreInt] = (distribution[scoreInt] ?: 0) + 1
        }
        
        return distribution
    }

    private fun get10PointScore(track: Track): Double {
        val service = trackerManager.get(track.trackerId)!!
        return service.get10PointScore(track)
    }
}
