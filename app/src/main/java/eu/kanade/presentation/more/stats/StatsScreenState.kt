package eu.kanade.presentation.more.stats

import androidx.compose.runtime.Immutable
import eu.kanade.presentation.more.stats.data.StatsData

sealed interface StatsScreenState {
    @Immutable
    data object Loading : StatsScreenState

    @Immutable
    data class Success(
        val overview: StatsData.Overview,
        val titles: StatsData.Titles,
        val chapters: StatsData.Chapters,
        val trackers: StatsData.Trackers,
        val extensions: StatsData.ExtensionUsage,
        val timeDistribution: StatsData.TimeDistribution,
        val genreAffinity: StatsData.GenreAffinity,
        val readHabits: StatsData.ReadHabits,
        val scores: StatsData.ScoreDistribution,
        val statuses: StatsData.StatusBreakdown,
        val feedActivity: StatsData.FeedActivity? = null,
    ) : StatsScreenState
}
