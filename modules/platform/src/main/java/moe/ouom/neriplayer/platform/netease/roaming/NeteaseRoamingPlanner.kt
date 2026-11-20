package moe.ouom.neriplayer.platform.netease.roaming

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
 * File: moe.ouom.neriplayer.platform.netease.roaming/NeteaseRoamingPlanner
 * Created: 2025/11/20
 */

/**
 * 漫游接口的期望批量。
 *
 * 登录态官方客户端一次给 3 首；匿名态实测只会给 1 首，且 `limit` 传多少都一样，
 * 所以调用方必须按"返回不足就继续要"来写，不能假设这一批一定满。
 */
const val NETEASE_ROAMING_BATCH_SIZE: Int = 3

/** 传给 `unplaySongIds` 的最近播放窗口，官方客户端也是 100 封顶。 */
const val NETEASE_ROAMING_EXCLUSION_WINDOW: Int = 100

/** 队列里少于等于这个数量就补货。 */
const val NETEASE_ROAMING_PREFETCH_THRESHOLD: Int = 2

/**
 * 漫游补给器。
 *
 * 漫游和普通歌单最本质的差别是**一批只有 3 首**，所以必须在播放器快断粮时提前续上，
 * 否则每 3 首就会卡一次。这个类只管两件事：
 *
 * 1. 记住最近播过的歌（滚动的 100 首窗口），作为下一次请求的 `unplaySongIds`；
 * 2. 收下服务端批次时去重，避免"拉回来又塞回最近播放"，导致在原地几首歌里打转。
 *
 * 它不碰网络、不碰播放器，纯内存状态机，方便单测。
 */
class NeteaseRoamingPlanner(
    private val exclusionWindow: Int = NETEASE_ROAMING_EXCLUSION_WINDOW
) {

    init {
        require(exclusionWindow > 0) { "exclusionWindow must be positive" }
    }

    private val recent = ArrayDeque<Long>()
    private val pending = ArrayDeque<Long>()

    val pendingCount: Int get() = pending.size

    /** 下一次请求要排除的歌曲 ID，按播放先后排序（最老的在前）。 */
    fun excludedSongIds(): List<Long> = recent.toList()

    /**
     * 一首歌真正开始播放时调用。
     *
     * 重复出现的歌会被挪到窗口末尾而不是重复计入，这样 `unplaySongIds` 始终是
     * "最近 100 首不重复歌曲"。
     */
    fun onSongStarted(songId: Long) {
        if (songId <= 0L) return
        recent.remove(songId)
        recent.addLast(songId)
        while (recent.size > exclusionWindow) {
            recent.removeFirst()
        }
        pending.remove(songId)
    }

    /**
     * 是否需要补货。
     *
     * [unplayedCount] 是播放队列里还没播的数量；缓冲里已有的也算库存，避免重复请求。
     */
    fun shouldPrefetch(
        unplayedCount: Int,
        threshold: Int = NETEASE_ROAMING_PREFETCH_THRESHOLD
    ): Boolean = pending.size + unplayedCount.coerceAtLeast(0) <= threshold

    /**
     * 收下一批结果，返回其中真正可用的 ID。
     *
     * 服务端可能返回刚播过的歌（尤其在快速切歌时），这里直接剔掉；
     * 同一批内部的重复也一并去掉。
     */
    fun acceptBatch(songIds: List<Long>): List<Long> {
        if (songIds.isEmpty()) return emptyList()
        val accepted = ArrayList<Long>(songIds.size)
        for (songId in songIds) {
            if (songId <= 0L) continue
            if (recent.contains(songId)) continue
            if (pending.contains(songId)) continue
            if (accepted.contains(songId)) continue
            pending.addLast(songId)
            accepted.add(songId)
        }
        return accepted
    }

    /** 取走缓冲里全部待入队的 ID。 */
    fun drainPending(): List<Long> {
        if (pending.isEmpty()) return emptyList()
        val drained = pending.toList()
        pending.clear()
        return drained
    }

    /** 丢弃缓冲但保留最近播放窗口（例如切换模式后重新取歌）。 */
    fun clearPending() {
        pending.clear()
    }

    /** 退出漫游时整体复位。 */
    fun reset() {
        recent.clear()
        pending.clear()
    }
}
