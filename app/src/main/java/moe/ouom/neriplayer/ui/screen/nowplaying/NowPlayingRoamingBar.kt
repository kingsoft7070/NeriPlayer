package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.core.roaming.NeteaseRoamingRuntime
import moe.ouom.neriplayer.data.model.netease.roaming.NeteaseRoamingChoice

/**
 * 播放页里的漫游控制条。
 *
 * 只有漫游进行中才渲染——平时播放页看不到它，符合"漫游是一种播放模式"的语义：
 * 开启后多出一组控件，关闭后整组消失。
 */
@Composable
internal fun NowPlayingRoamingBar(modifier: Modifier = Modifier) {
    val state by NeteaseRoamingRuntime.state.collectAsStateWithLifecycle()
    if (!state.running) return

    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        TextButton(onClick = { showPicker = true }) {
            Text(
                text = stringResource(AppR.string.roaming_title) + " · " +
                        roamingBarLabel(state.selected),
                style = MaterialTheme.typography.labelLarge
            )
        }
        TextButton(onClick = { NeteaseRoamingRuntime.stop() }) {
            Text(
                text = stringResource(AppR.string.roaming_stop),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }

    if (showPicker) {
        RoamingChoiceDialog(
            choices = state.catalog.choices,
            selectedKey = state.selected?.key,
            onSelect = {
                NeteaseRoamingRuntime.select(it)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun RoamingChoiceDialog(
    choices: List<NeteaseRoamingChoice>,
    selectedKey: String?,
    onSelect: (NeteaseRoamingChoice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(AppR.string.roaming_stop))
            }
        },
        title = { Text(text = stringResource(AppR.string.roaming_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(AppR.string.roaming_mode_section),
                    style = MaterialTheme.typography.labelMedium
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(choices, key = { it.key }) { choice ->
                        FilterChip(
                            selected = choice.key == selectedKey,
                            onClick = { onSelect(choice) },
                            label = { Text(roamingBarLabel(choice)) }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun roamingBarLabel(choice: NeteaseRoamingChoice?): String {
    if (choice == null) return stringResource(AppR.string.roaming_title)
    if (choice.title.isNotBlank()) return choice.title
    return when (choice.code) {
        "NIGHT_EMO" -> stringResource(AppR.string.roaming_scene_night_emo)
        "EXERCISE" -> stringResource(AppR.string.roaming_scene_exercise)
        "SLEEP_HELP" -> stringResource(AppR.string.roaming_scene_sleep)
        "RELAX" -> stringResource(AppR.string.roaming_scene_relax)
        "FOCUS" -> stringResource(AppR.string.roaming_scene_focus)
        "COMMUTE" -> stringResource(AppR.string.roaming_scene_commute)
        else -> choice.code
    }
}
