package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.WheelTextPicker
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

@Composable
fun DeleteChaptersDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.are_you_sure))
        },
        text = {
            Text(text = stringResource(MR.strings.confirm_delete_chapters))
        },
    )
}

@Composable
fun SetIntervalDialog(
    manga: Manga,
    onDismissRequest: () -> Unit,
    onValueChanged: ((Int) -> Unit)? = null,
    onScheduleChanged: ((Int, Int) -> Unit)? = null,
) {
    val interval = manga.fetchInterval
    val nextUpdate = manga.expectedNextUpdate
    var selectedInterval by rememberSaveable { mutableIntStateOf(if (interval < 0) -interval else 0) }
    var selectedDays by rememberSaveable { mutableIntStateOf(manga.fetchIntervalDays) }
    var selectedTime by rememberSaveable { mutableIntStateOf(manga.fetchIntervalTime) }

    val nextUpdateDays = remember(nextUpdate) {
        return@remember if (nextUpdate != null) {
            val now = Instant.now()
            now.until(nextUpdate, ChronoUnit.DAYS).toInt().coerceAtLeast(0)
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.pref_library_update_smart_update)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (nextUpdateDays != null && nextUpdateDays >= 0 && interval >= 0) {
                    Text(
                        stringResource(
                            MR.strings.manga_interval_expected_update,
                            pluralStringResource(
                                MR.plurals.day,
                                count = nextUpdateDays,
                                nextUpdateDays,
                            ),
                            pluralStringResource(
                                MR.plurals.day,
                                count = interval.absoluteValue,
                                interval.absoluteValue,
                            ),
                        ),
                    )
                } else {
                    Text(
                        stringResource(MR.strings.manga_interval_expected_update_null),
                    )
                }
                Spacer(Modifier.height(MaterialTheme.padding.small))

                if (onValueChanged != null && (!isReleaseBuildType)) {
                    Text(
                        text = stringResource(MR.strings.manga_interval_custom_amount),
                        style = MaterialTheme.typography.labelMedium,
                    )

                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val size = DpSize(width = maxWidth / 2, height = 128.dp)
                        val items = (0..FetchInterval.MAX_INTERVAL)
                            .map {
                                if (it == 0) {
                                    stringResource(MR.strings.label_default)
                                } else {
                                    it.toString()
                                }
                            }
                            .toImmutableList()
                        WheelTextPicker(
                            items = items,
                            size = size,
                            startIndex = selectedInterval,
                            onSelectionChanged = { selectedInterval = it },
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.medium))

                Text(
                    text = stringResource(MR.strings.manga_update_schedule),
                    style = MaterialTheme.typography.labelMedium,
                )

                val days = listOf(
                    MR.strings.day_monday,
                    MR.strings.day_tuesday,
                    MR.strings.day_wednesday,
                    MR.strings.day_thursday,
                    MR.strings.day_friday,
                    MR.strings.day_saturday,
                    MR.strings.day_sunday,
                )

                days.forEachIndexed { index, dayRes ->
                    val bit = 1 shl index
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedDays and bit != 0,
                            onCheckedChange = { checked ->
                                selectedDays = if (checked) {
                                    selectedDays or bit
                                } else {
                                    selectedDays and bit.inv()
                                }
                            },
                        )
                        Text(text = stringResource(dayRes))
                    }
                }

                Spacer(Modifier.height(MaterialTheme.padding.small))

                Text(
                    text = stringResource(MR.strings.manga_update_schedule_time),
                    style = MaterialTheme.typography.labelMedium,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hourItems = (0..23).map { it.toString().padStart(2, '0') }.toImmutableList()
                    val minuteItems = (0..59).map { it.toString().padStart(2, '0') }.toImmutableList()

                    WheelTextPicker(
                        items = hourItems,
                        size = DpSize(64.dp, 128.dp),
                        startIndex = selectedTime / 60,
                        onSelectionChanged = { selectedTime = it * 60 + (selectedTime % 60) },
                    )
                    Text(" : ", style = MaterialTheme.typography.titleLarge)
                    WheelTextPicker(
                        items = minuteItems,
                        size = DpSize(64.dp, 128.dp),
                        startIndex = selectedTime % 60,
                        onSelectionChanged = { selectedTime = (selectedTime / 60) * 60 + it },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onValueChanged?.invoke(selectedInterval)
                    onScheduleChanged?.invoke(selectedDays, selectedTime)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}
