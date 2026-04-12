package eu.kanade.presentation.more.stats.data

sealed interface StatsData {

    data class Overview(
        val libraryMangaCount: Int,
        val completedMangaCount: Int,
        val totalReadDuration: Long,
    ) : StatsData

    data class Titles(
        val globalUpdateItemCount: Int,
        val startedMangaCount: Int,
        val localMangaCount: Int,
    ) : StatsData

    data class Chapters(
        val totalChapterCount: Int,
        val readChapterCount: Int,
        val downloadCount: Int,
    ) : StatsData

    data class Trackers(
        val trackedTitleCount: Int,
        val meanScore: Double,
        val trackerCount: Int,
    ) : StatsData

    data class ExtensionUsage(
        val topExtensions: List<ExtensionInfo>,
    ) : StatsData

    data class TimeDistribution(
        val daysDistribution: Map<Int, Long>, // DayOfWeek to session count
        val weeklyHeatmap: Map<Int, Int>, // Hour of day to total frequency
    ) : StatsData

    data class GenreAffinity(
        val genreScores: List<Pair<String, Int>>, // Genre to count
    ) : StatsData

    data class ScoreDistribution(
        val scoredMangaCount: Int,
        val distribution: Map<Int, Int>, // Score (1-10) to count
    ) : StatsData

    data class StatusBreakdown(
        val completedCount: Int,
        val ongoingCount: Int,
        val droppedCount: Int,
        val onHoldCount: Int,
        val planToReadCount: Int,
    ) : StatsData

    data class ReadHabits(
        val topDayManga: String?,
        val topMonthManga: String?,
        val preferredReadTime: String,
        val avgSessionsPerWeek: Double,
    ) : StatsData

    data class FeedActivity(
        val activity: List<SourceActivity>,
    ) : StatsData
}

data class ExtensionInfo(
    val name: String,
    val count: Int,
    val repo: String?,
)

data class SourceActivity(
    val sourceId: Long,
    val sourceName: String,
    val feedName: String,
    val fetchCount: Int,
    val openCount: Int,
    val readCount: Int,
    val completeCount: Int,
)
