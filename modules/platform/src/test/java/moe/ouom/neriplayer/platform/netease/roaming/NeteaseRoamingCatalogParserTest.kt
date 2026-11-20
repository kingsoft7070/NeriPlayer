package moe.ouom.neriplayer.platform.netease.roaming

import java.io.IOException
import moe.ouom.neriplayer.data.model.netease.roaming.NETEASE_ROAMING_MODE_DEFAULT
import moe.ouom.neriplayer.data.model.netease.roaming.NETEASE_ROAMING_SCENE_MODE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseRoamingCatalogParserTest {

    private val payload = """
        {
          "code": 200,
          "data": {
            "crossPlatformResource": {
              "dslData": {
                "recommendModeList": [
                  {"code": "DEFAULT", "title": "默认模式", "subTitle": "沿着目前喜好继续聆听"},
                  {"code": "FAMILIAR", "title": "熟悉模式"},
                  {"code": "EXPLORE", "title": "探索模式"},
                  {"code": "PUZZLE_MODE_RCMD", "title": "拼图模式"}
                ],
                "currentSceneList": [
                  {"code": "NIGHT_EMO", "title": "伤感"},
                  {"code": "FOCUS", "title": "专注"}
                ],
                "privateFmModeExt": {
                  "PUZZLE_MODE_RCMD": {"": {"fmCascadeModeStr": "PUZZLE_CASCADE"}},
                  "SCENE_RCMD": {"FOCUS": {"fmCascadeModeStr": "FOCUS_CASCADE"}}
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parses modes and scenes from the position payload`() {
        val catalog = NeteaseRoamingCatalogParser.parse(payload)

        assertFalse(catalog.fallback)
        assertEquals(4, catalog.modes.size)
        assertEquals(2, catalog.scenes.size)
        assertEquals(6, catalog.choices.size)
        assertEquals("默认模式", catalog.find(NETEASE_ROAMING_MODE_DEFAULT)?.title)
        assertEquals("沿着目前喜好继续聆听", catalog.find(NETEASE_ROAMING_MODE_DEFAULT)?.subtitle)
        assertEquals(NETEASE_ROAMING_MODE_DEFAULT, catalog.defaultChoice()?.code)
    }

    @Test
    fun `scenes always travel as SCENE_RCMD plus a subMode`() {
        val focus = NeteaseRoamingCatalogParser.parse(payload).find("scene:FOCUS")

        requireNotNull(focus)
        assertTrue(focus.scene)
        assertEquals(NETEASE_ROAMING_SCENE_MODE, focus.wireMode)
        assertEquals("FOCUS", focus.wireSubMode)
        assertEquals("专注", focus.title)
    }

    @Test
    fun `modes carry an empty subMode and keep their own code as mode`() {
        val explore = NeteaseRoamingCatalogParser.parse(payload).find("EXPLORE")

        requireNotNull(explore)
        assertFalse(explore.scene)
        assertEquals("EXPLORE", explore.wireMode)
        assertEquals("", explore.wireSubMode)
    }

    @Test
    fun `cascade is read from privateFmModeExt for modes and scenes`() {
        val catalog = NeteaseRoamingCatalogParser.parse(payload)

        assertEquals("PUZZLE_CASCADE", catalog.find("PUZZLE_MODE_RCMD")?.cascade)
        assertEquals("FOCUS_CASCADE", catalog.find("scene:FOCUS")?.cascade)
    }

    @Test
    fun `missing lists fall back to the built-in catalog`() {
        val catalog = NeteaseRoamingCatalogParser.parse("""{"code":200,"data":{}}""")

        assertTrue(catalog.fallback)
        assertTrue(catalog.modes.isNotEmpty())
        assertTrue(catalog.scenes.isNotEmpty())
        assertEquals(NETEASE_ROAMING_MODE_DEFAULT, catalog.defaultChoice()?.code)
    }

    @Test
    fun `unparseable payload falls back instead of crashing`() {
        assertTrue(NeteaseRoamingCatalogParser.parse("not json").fallback)
        assertTrue(NeteaseRoamingCatalogParser.parse("").fallback)
    }

    @Test(expected = IOException::class)
    fun `non 200 code surfaces as io error`() {
        NeteaseRoamingCatalogParser.parse("""{"code":301,"message":"need login"}""")
    }

    @Test
    fun `duplicate codes are collapsed`() {
        val raw = """
            {"code":200,"data":{"dslData":{"recommendModeList":[
              {"code":"DEFAULT","title":"A"},{"code":"DEFAULT","title":"B"}]}}}
        """.trimIndent()

        val catalog = NeteaseRoamingCatalogParser.parse(raw)

        assertEquals(1, catalog.modes.size)
        assertEquals("A", catalog.modes.first().title)
    }
}
