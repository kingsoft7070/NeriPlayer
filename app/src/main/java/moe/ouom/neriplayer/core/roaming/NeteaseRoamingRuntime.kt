package moe.ouom.neriplayer.core.roaming

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/** 匿名态一次只给 1 首，允许重试几轮凑批。 */
private const val ROAMING_MAX_FETCH_ATTEMPTS = 6

/** 目录是运营位数据，缓存一天足够；过期后进页面再刷新。 */
private const val ROAMING_CATALOG_TTL_MS = 24L * 60L * 60L * 1000L

/** 补货失败后的退避基数，避免每换一首歌就打一次接口。 */
private const val ROAMING_REFILL_BACKOFF_MS = 30_000L

private const val ROAMING_CANDIDATE_LIMIT = 50

data class NeteaseRoamingUiState(
    val loadingCatalog: Boolean = true,
    val catalog: NeteaseRoamingCatalog = NeteaseRoamingCatalog.fallback(),
    val selected: NeteaseRoamingChoice? = null,
    val running: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val offline: Boolean = false
)

/**
 * 漫游的常驻运行体。
 *
 * 之所以不放在 ViewModel 里：漫游是要在**离开页面之后继续**的行为。
 * ViewModel 跟着导航条目销毁，放进去就会出现"退出漫游页 → 队列播完就断"，
 * 那等于功能没实现。这里挂在进程级，页面只做展示与操作入口。
 */
internal object NeteaseRoamingRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val planner = NeteaseRoamingPlanner()
    private val client get() = AppContainer.neteaseClient

    private val _state = MutableStateFlow(NeteaseRoamingUiState())
    val state: StateFlow<NeteaseRoamingUiState> = _state.asStateFlow()

    private var refillJob: Job? = null
    private var observerJob: Job? = null
    private var active = false
    private var catalogLoadedAtMs = 0L

    fun ensureCatalog(force: Boolean = false) {
        val fresh = SystemClock.elapsedRealtime() - catalogLoadedAtMs < ROAMING_CATALOG_TTL_MS
        if (!force && fresh && _state.value.catalog.fallback.not()) return
        if (_state.value.loadingCatalog && catalogLoadedAtMs > 0L) return
        scope.launch {
            _state.update { it.copy(loadingCatalog = true) }
            val catalog = runCatching {
                withContext(Dispatchers.IO) {
                    NeteaseRoamingCatalogParser.parse(client.getRoamingCatalog())
                }
            }.getOrElse { NeteaseRoamingCatalog.fallback() }
            catalogLoadedAtMs = SystemClock.elapsedRealtime()
            _state.update { state ->
                state.copy(
                    loadingCatalog = false,
                    catalog = catalog,
                    selected = state.selected ?: catalog.defaultChoice()
                )
            }
        }
    }

    fun select(choice: NeteaseRoamingChoice) {
        if (_state.value.selected?.key == choice.key) return
        planner.clearPending()
        _state.update { it.copy(selected = choice, failed = false) }
    }

    fun start() {
        val current = _state.value
        val choice = current.selected ?: return
        if (current.busy || current.running) return
        scope.launch {
            _state.update { it.copy(busy = true, failed = false) }
            val songs = runCatching { fetchBatch(choice) }.getOrElse {
                _state.update { state -> state.copy(busy = false, failed = true) }
                return@launch
            }
            if (songs.isEmpty()) {
                _state.update { it.copy(busy = false, failed = true) }
                return@launch
            }
            active = true
            observePlayback()
            PlayerManager.playPlaylist(songs, 0)
            _state.update { it.copy(busy = false, running = true) }
        }
    }

    fun stop() {
        active = false
        planner.reset()
        refillJob?.cancel()
        refillJob = null
        observerJob?.cancel()
        observerJob = null
        _state.update { it.copy(running = false) }
    }

    /**
     * 播放器换歌是唯一的驱动信号：推进排除窗口，队列见底就补货。
     * 用"当前歌在队列里的位置"推算剩余量，避免依赖 PlayerManager 的 internal 索引。
     */
    private fun observePlayback() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            PlayerManager.currentSongFlow.collect { song ->
                if (!active || song == null) return@collect
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
        val choice = _state.value.selected ?: return
        refillJob = scope.launch {
            val songs = runCatching { fetchBatch(choice) }.getOrElse {
                // 失败不打断播放：队列还有存量，退避一段时间再试。
                kotlinx.coroutines.delay(ROAMING_REFILL_BACKOFF_MS)
                return@launch
            }
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
                parseNeteaseHomeSongs(raw, limit = ROAMING_CANDIDATE_LIMIT)
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
