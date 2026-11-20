package moe.ouom.neriplayer.data.model.netease.roaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseRoamingModelsTest {

    @Test
    fun `fallback catalog always yields a selectable default`() {
        val catalog = NeteaseRoamingCatalog.fallback()

        assertTrue(catalog.fallback)
        assertTrue(catalog.modes.isNotEmpty())
        assertTrue(catalog.scenes.isNotEmpty())
        assertEquals(NETEASE_ROAMING_MODE_DEFAULT, catalog.defaultChoice()?.code)
    }

    @Test
    fun `scenes translate into SCENE_RCMD plus subMode`() {
        val scene = NeteaseRoamingCatalog.fallback().find("scene:FOCUS")

        requireNotNull(scene)
        assertTrue(scene.scene)
        assertEquals(NETEASE_ROAMING_SCENE_MODE, scene.wireMode)
        assertEquals("FOCUS", scene.wireSubMode)
    }

    @Test
    fun `plain modes keep their own code and no subMode`() {
        val familiar = NeteaseRoamingCatalog.fallback().find(NETEASE_ROAMING_MODE_FAMILIAR)

        requireNotNull(familiar)
        assertEquals(NETEASE_ROAMING_MODE_FAMILIAR, familiar.wireMode)
        assertEquals("", familiar.wireSubMode)
    }

    @Test
    fun `unknown or empty keys resolve to null`() {
        val catalog = NeteaseRoamingCatalog.fallback()

        assertNull(catalog.find(null))
        assertNull(catalog.find("does-not-exist"))
    }

    @Test
    fun `default choice falls back to the first mode when DEFAULT is absent`() {
        val custom = NeteaseRoamingCatalog(
            modes = listOf(NeteaseRoamingChoice("EXPLORE", "探索")),
            scenes = emptyList()
        )

        assertEquals("EXPLORE", custom.defaultChoice()?.code)
    }
}
