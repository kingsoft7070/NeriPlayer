package moe.ouom.neriplayer.platform.netease.roaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseRoamingPlannerTest {

    @Test
    fun `prefetch starts when the queue runs dry`() {
        val planner = NeteaseRoamingPlanner()

        assertTrue(planner.shouldPrefetch(unplayedCount = 0))
        assertTrue(planner.shouldPrefetch(unplayedCount = 2))
        assertFalse(planner.shouldPrefetch(unplayedCount = 3))
    }

    @Test
    fun `pending songs count towards the buffer`() {
        val planner = NeteaseRoamingPlanner()
        planner.acceptBatch(listOf(1L, 2L, 3L))

        assertFalse(planner.shouldPrefetch(unplayedCount = 5))
        assertFalse(planner.shouldPrefetch(unplayedCount = 0))
    }

    @Test
    fun `exclusion window keeps only the most recent hundred songs`() {
        val planner = NeteaseRoamingPlanner()

        for (songId in 1L..101L) {
            planner.onSongStarted(songId)
        }

        val excluded = planner.excludedSongIds()
        assertEquals(NETEASE_ROAMING_EXCLUSION_WINDOW, excluded.size)
        assertEquals(2L, excluded.first())
        assertEquals(101L, excluded.last())
    }

    @Test
    fun `replaying a song moves it to the end instead of duplicating it`() {
        val planner = NeteaseRoamingPlanner()

        planner.onSongStarted(7L)
        planner.onSongStarted(9L)
        planner.onSongStarted(7L)

        assertEquals(listOf(9L, 7L), planner.excludedSongIds())
    }

    @Test
    fun `batches are filtered against recent and pending songs`() {
        val planner = NeteaseRoamingPlanner()
        planner.onSongStarted(1L)

        val accepted = planner.acceptBatch(listOf(1L, 2L, 2L, 3L, -5L))

        assertEquals(listOf(2L, 3L), accepted)
        assertEquals(2, planner.pendingCount)
    }

    @Test
    fun `a song already buffered is dropped once it starts playing`() {
        val planner = NeteaseRoamingPlanner()
        planner.acceptBatch(listOf(4L, 5L))

        planner.onSongStarted(4L)

        assertEquals(1, planner.pendingCount)
        assertEquals(listOf(5L), planner.drainPending())
        assertEquals(listOf(4L), planner.excludedSongIds())
    }

    @Test
    fun `draining empties the buffer but keeps the exclusion window`() {
        val planner = NeteaseRoamingPlanner()
        planner.acceptBatch(listOf(8L, 9L))

        assertEquals(listOf(8L, 9L), planner.drainPending())
        assertEquals(0, planner.pendingCount)
        assertTrue(planner.drainPending().isEmpty())

        planner.onSongStarted(8L)
        assertEquals(listOf(8L), planner.excludedSongIds())
    }

    @Test
    fun `clearing pending keeps the exclusion window, reset drops everything`() {
        val planner = NeteaseRoamingPlanner()
        planner.onSongStarted(3L)
        planner.acceptBatch(listOf(6L))

        planner.clearPending()

        assertEquals(0, planner.pendingCount)
        assertEquals(listOf(3L), planner.excludedSongIds())

        planner.reset()

        assertTrue(planner.excludedSongIds().isEmpty())
        assertTrue(planner.shouldPrefetch(unplayedCount = 0))
    }

    @Test
    fun `blocked songs never enter the buffer and leave it immediately`() {
        val planner = NeteaseRoamingPlanner()
        planner.blockSong(5L)

        assertTrue(planner.acceptBatch(listOf(5L, 6L)).contains(6L))
        assertFalse(planner.acceptBatch(listOf(5L)).contains(5L))
        assertTrue(planner.isBlocked(5L))
        assertEquals(listOf(5L), planner.blockedSongIds())
    }

    @Test
    fun `blocking a buffered song drops it from pending`() {
        val planner = NeteaseRoamingPlanner()
        planner.acceptBatch(listOf(11L, 12L))

        planner.blockSong(11L)

        assertEquals(1, planner.pendingCount)
        assertEquals(listOf(12L), planner.drainPending())
    }

    @Test
    fun `blocked songs are restored without touching the recent window`() {
        val planner = NeteaseRoamingPlanner()
        planner.onSongStarted(21L)
        planner.restoreBlocked(listOf(22L, 23L))

        assertEquals(listOf(21L), planner.excludedSongIds())
        assertEquals(listOf(22L, 23L), planner.blockedSongIds())
    }

    @Test
    fun `negative and zero ids are ignored`() {
        val planner = NeteaseRoamingPlanner()
        planner.onSongStarted(0L)
        planner.onSongStarted(-1L)

        assertTrue(planner.excludedSongIds().isEmpty())
        assertTrue(planner.acceptBatch(listOf(0L, -1L)).isEmpty())
    }
}
