package moe.ouom.neriplayer.ui.viewmodel.roaming

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
 * File: moe.ouom.neriplayer.ui.viewmodel.roaming/NeteaseRoamingViewModel
 * Created: 2025/11/20
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.roaming.NeteaseRoamingCatalog
import moe.ouom.neriplayer.data.model.netease.roaming.NeteaseRoamingChoice
import moe.ouom.neriplayer.platform.netease.roaming.NETEASE_ROAMING_BATCH_SIZE
import moe.ouom.neriplayer.platform.netease.roaming.NeteaseRoamingCatalogParser
import moe.ouom.neriplayer.platform.netease.roaming.NeteaseRoamingPlanner
import moe.ouom.neriplayer.ui.viewmodel.tab.parseNeteaseHomeSongs

/** 匿名态一次只给 1 首，所以补货时允许多要几轮。 */
private const val ROAMING_MAX_FETCH_ATTEMPTS = 6

data class NeteaseRoamingUiState(
    val loadingCatalog: Boolean = true,
    val catalog: NeteaseRoamingCatalog = NeteaseRoamingCatalog.fallback(),
    val selected: NeteaseRoamingChoice? = null,
    val running: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false
)

/**
 * 漫游的对外入口。
 *
 * 职责边界：只负责"选档位 → 取歌 → 喂队列"，不碰播放器的播放/暂停，
 * 也不改 PlayerManager，续歌靠监听 [PlayerManager.currentSongFlow] 完成。
 */
class NeteaseRoamingViewModel(application: Application) : AndroidViewModel(application) {

    private val client = AppContainer.neteaseClient
    private val planner = NeteaseRoamingPlanner()

    private val _uiState = MutableStateFlow(NeteaseRoamingUiState())
    val uiState: StateFlow<NeteaseRoamingUiState> = _uiState.asStateFlow()

    private var sessionActive = false
    private var refillJob: Job? = null

    init {
        refreshCatalog()
        observePlayback()
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingCatalog = true) }
            val catalog = runCatching {
                withContext(Dispatchers.IO) {
                    NeteaseRoamingCatalogParser.parse(client.getRoamingCatalog())
                }
            }.getOrElse { NeteaseRoamingCatalog.fallback() }
            _uiState.update { state ->
                state.copy(
                    loadingCatalog = false,
                    catalog = catalog,
                    selected = state.selected ?: catalog.defaultChoice()
                )
            }
        }
    }

    fun select(choice: NeteaseRoamingChoice) {
        if (_uiState.value.selected?.key == choice.key) return
        // 换档位意味着之前预取的歌不再符合口味，直接丢掉缓冲。
        planner.clearPending()
        _uiState.update { it.copy(selected = choice, failed = false) }
    }

    fun start() {
        val state = _uiState.value
        val choice = state.selected ?: return
        if (state.busy || state.running) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, failed = false) }
            val songs = runCatching { fetchBatch(choice) }.getOrElse {
                _uiState.update { current -> current.copy(busy = false, failed = true) }
                return@launch
            }
            if (songs.isEmpty()) {
                _uiState.update { it.copy(busy = false, failed = true) }
                return@launch
            }
            sessionActive = true
            PlayerManager.playPlaylist(songs, 0)
            _uiState.update { it.copy(busy = false, running = true) }
        }
    }

    fun stop() {
        sessionActive = false
        planner.reset()
        refillJob?.cancel()
        refillJob = null
        _uiState.update { it.copy(running = false) }
    }

    override fun onCleared() {
        sessionActive = false
        refillJob?.cancel()
        refillJob = null
        super.onCleared()
    }

    /**
     * 播放器每次真正换歌时推进排除窗口，并在快断粮时补货。
     *
     * 这里用"当前歌在队列里的位置"推算剩余量，而不是读 PlayerManager 的
     * internal currentIndex——后者跨模块不可见。
     */
    private fun observePlayback() {
        viewModelScope.launch {
            PlayerManager.currentSongFlow.collect { song ->
                if (!sessionActive || song == null) return@collect
                planner.onSongStarted(song.id)
                val queue = PlayerManager.currentQueueFlow.value
                val index = queue.indexOfFirst { it.id == song.id }
                val unplayed = if (index >= 0) queue.size - index - 1 else queue.size
                if (planner.shouldPrefetch(unplayed) && refillJob?.isActive != true) {
                    requestRefill()
                }
            }
        }
    }

    private fun requestRefill() {
        val choice = _uiState.value.selected ?: return
        refillJob = viewModelScope.launch {
            val songs = runCatching { fetchBatch(choice) }.getOrElse {
                _uiState.update { current -> current.copy(failed = true) }
                return@launch
            }
            // 补给失败不打断播放：队列里还有存量，下一首歌再试。
            if (songs.isEmpty()) return@launch
            songs.forEach { song -> PlayerManager.addToQueueEnd(song) }
        }
    }

    private suspend fun fetchBatch(choice: NeteaseRoamingChoice): List<SongItem> {
        val collected = ArrayList<SongItem>(NETEASE_ROAMING_BATCH_SIZE)
        repeat(ROAMING_MAX_FETCH_ATTEMPTS) {
            val raw = withContext(Dispatchers.IO) {
                client.getRoamingSongs(
                    mode = choice.wireMode,
                    subMode = choice.wireSubMode,
                    excludedSongIds = planner.excludedSongIds(),
                    cascade = choice.cascade,
                    limit = NETEASE_ROAMING_BATCH_SIZE
                )
            }
            val batch = withContext(Dispatchers.Default) {
                parseNeteaseHomeSongs(raw, limit = NETEASE_ROAMING_CANDIDATE_LIMIT)
            }
            if (batch.isEmpty()) return@repeat
            val acceptedIds = planner.acceptBatch(batch.map { it.id })
            if (acceptedIds.isEmpty()) return@repeat
            val byId = batch.associateBy { it.id }
            acceptedIds.forEach { id -> byId[id]?.let(collected::add) }
            if (collected.size >= NETEASE_ROAMING_BATCH_SIZE) return collected
        }
        return collected
    }
}

private const val NETEASE_ROAMING_CANDIDATE_LIMIT = 50
