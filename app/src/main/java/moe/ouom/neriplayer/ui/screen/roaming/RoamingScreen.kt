package moe.ouom.neriplayer.ui.screen.roaming

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.roaming/RoamingScreen
 * Created: 2025/11/20
 */

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.roaming.RoamingBlockReason
import moe.ouom.neriplayer.data.model.netease.roaming.NeteaseRoamingChoice
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.viewmodel.roaming.NeteaseRoamingViewModel

/** 漫游页路由；与 Destinations 里的 tab 路由区分开，避免影响导航栏枚举。 */
const val NETEASE_ROAMING_ROUTE: String = "netease_roaming"

/**
 * 漫游页。
 *
 * 交互只有三件事：选档位、开始、结束。真正的"一直放下去"由 ViewModel 观察队列完成，
 * 这里不持有任何播放状态，避免和 NowPlaying 抢状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoamingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm: NeteaseRoamingViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                NeteaseRoamingViewModel(appContext as Application)
            }
        }
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(AppR.string.roaming_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(AppR.string.roaming_stop)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NeriDesign.tokens.pageHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(NeriDesign.tokens.sectionSpacing)
        ) {
            Text(
                text = stringResource(AppR.string.roaming_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.loadingCatalog) {
                Text(
                    text = stringResource(AppR.string.roaming_idle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ChoiceRow(
                    title = stringResource(AppR.string.roaming_mode_section),
                    choices = state.catalog.modes,
                    selectedKey = state.selected?.key,
                    onSelect = vm::select
                )
                if (state.catalog.scenes.isNotEmpty()) {
                    ChoiceRow(
                        title = stringResource(AppR.string.roaming_scene_section),
                        choices = state.catalog.scenes,
                        selectedKey = state.selected?.key,
                        onSelect = vm::select
                    )
                }
                if (state.catalog.fallback) {
                    Text(
                        text = stringResource(AppR.string.roaming_fallback_catalog),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (state.running) {
                Text(
                    text = stringResource(AppR.string.roaming_running),
                    style = MaterialTheme.typography.titleMedium
                )
                currentSong?.let { song ->
                    Text(
                        text = "${song.name} · ${song.artist}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.blockedReason?.let { reason ->
                Text(
                    text = stringResource(
                        when (reason) {
                            RoamingBlockReason.OFFLINE -> AppR.string.roaming_blocked_offline
                            RoamingBlockReason.LISTEN_TOGETHER ->
                                AppR.string.roaming_blocked_listen_together
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.failed) {
                Text(
                    text = stringResource(AppR.string.roaming_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = { if (state.running) vm.stop() else vm.start() },
                enabled = !state.busy && state.selected != null && state.blockedReason == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        if (state.running) AppR.string.roaming_stop else AppR.string.roaming_start
                    )
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    choices: List<NeteaseRoamingChoice>,
    selectedKey: String?,
    onSelect: (NeteaseRoamingChoice) -> Unit
) {
    if (choices.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(items = choices, key = { it.key }) { choice ->
                FilterChip(
                    selected = choice.key == selectedKey,
                    onClick = { onSelect(choice) },
                    label = { Text(text = choice.title.ifBlank { choice.code }) }
                )
            }
        }
    }
}
