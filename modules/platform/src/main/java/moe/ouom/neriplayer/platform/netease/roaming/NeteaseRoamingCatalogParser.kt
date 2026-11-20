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
 * File: moe.ouom.neriplayer.platform.netease.roaming/NeteaseRoamingCatalogParser
 * Created: 2025/11/20
 */

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import moe.ouom.neriplayer.data.model.netease.roaming.NETEASE_ROAMING_SCENE_MODE
import moe.ouom.neriplayer.data.model.netease.roaming.NeteaseRoamingCatalog
import moe.ouom.neriplayer.data.model.netease.roaming.NeteaseRoamingChoice

/**
 * 漫游入口目录所在的运营位编码。
 *
 * 请求常量留在 api.client 包内（分层门禁不允许 api.client 反向依赖本包），
 * 这里只保留解析需要的口径说明。
 */


/**
 * 目录接口的 extJson。
 *
 * 这几个字段是网易客户端拉这个运营位时固定带上的，缺字段时服务端可能返回空列表，
 * 所以即便当前用不到 `userSetFMMode` 之类也要原样传。
 */
internal const val NETEASE_ROAMING_CATALOG_EXT_JSON: String =
    """{"clientLibraAbTest":{},"isHomePageNewFramework":true,"userSetFMMode":false,"enableAutoPlay":false}"""

private const val NETEASE_ROAMING_MAX_CODE_LENGTH = 100

/**
 * 解析漫游入口目录。
 *
 * 目录接口是运营位接口，结构随时可能变，因此这里对每一层都做空值兜底：
 * 拿不到列表时退回 [NeteaseRoamingCatalog.fallback]，而不是抛错让入口消失。
 */
object NeteaseRoamingCatalogParser {

    @Throws(IOException::class)
    fun parse(raw: String): NeteaseRoamingCatalog {
        val root = runCatching { JsonParser.parseString(raw)?.asJsonObject }
            .getOrNull()
            ?: return NeteaseRoamingCatalog.fallback()

        val code = root.intOrNull("code")
        if (code != null && code != 200) {
            throw IOException("netease roaming catalog rejected: code=$code")
        }

        val data = root.obj("data")
        val dsl = data?.obj("crossPlatformResource")?.obj("dslData")
            ?: data?.obj("dslData")
            ?: data
            ?: root
        val ext = dsl.obj("privateFmModeExt")

        val modes = parseChoices(dsl.arr("recommendModeList"), scene = false, ext = ext)
        val scenes = parseChoices(dsl.arr("currentSceneList"), scene = true, ext = ext)

        if (modes.isEmpty() && scenes.isEmpty()) return NeteaseRoamingCatalog.fallback()

        val fallback = NeteaseRoamingCatalog.fallback()
        return NeteaseRoamingCatalog(
            modes = modes.ifEmpty { fallback.modes },
            scenes = scenes.ifEmpty { fallback.scenes },
            fallback = modes.isEmpty() || scenes.isEmpty()
        )
    }

    private fun parseChoices(
        array: JsonArray?,
        scene: Boolean,
        ext: JsonObject?
    ): List<NeteaseRoamingChoice> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val code = row.text("code")
            val title = row.text("title")
            if (code.isEmpty() || code.length > NETEASE_ROAMING_MAX_CODE_LENGTH) return@mapNotNull null
            val cascade = row.text("fmCascadeModeStr").ifEmpty {
                cascadeOf(ext = ext, mode = if (scene) NETEASE_ROAMING_SCENE_MODE else code, subMode = if (scene) code else "")
            }
            NeteaseRoamingChoice(
                code = code,
                title = title,
                subtitle = row.text("subTitle"),
                scene = scene,
                cascade = cascade,
                icon = row.text("icon")
            )
        }.distinctBy { it.key }
    }

    /**
     * 曲风级联参数藏在 `privateFmModeExt.<mode>.<subMode>.fmCascadeModeStr`，
     * 它是"拼图/场景"这类模式的附加筛选条件，取不到就留空。
     */
    private fun cascadeOf(ext: JsonObject?, mode: String, subMode: String): String =
        ext?.obj(mode)?.obj(subMode)?.text("fmCascadeModeStr").orEmpty()
}

private fun JsonObject?.obj(key: String): JsonObject? =
    this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject?.arr(key: String): JsonArray? =
    this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject?.text(key: String): String =
    this?.get(key)
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.trim()
        .orEmpty()

private fun JsonObject?.intOrNull(key: String): Int? {
    val primitive = this?.get(key)?.takeIf { it.isJsonPrimitive } ?: return null
    return runCatching { primitive.asInt }.getOrNull()
}
