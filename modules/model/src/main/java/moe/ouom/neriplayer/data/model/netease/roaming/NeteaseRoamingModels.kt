package moe.ouom.neriplayer.data.model.netease.roaming

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
 * File: moe.ouom.neriplayer.data.model.netease.roaming/NeteaseRoamingModels
 * Created: 2025/11/20
 */

/** 场景类漫游统一以 mode=SCENE_RCMD 请求，场景码放在 subMode。 */
const val NETEASE_ROAMING_SCENE_MODE: String = "SCENE_RCMD"

const val NETEASE_ROAMING_MODE_DEFAULT: String = "DEFAULT"
const val NETEASE_ROAMING_MODE_FAMILIAR: String = "FAMILIAR"
const val NETEASE_ROAMING_MODE_EXPLORE: String = "EXPLORE"
const val NETEASE_ROAMING_MODE_PUZZLE: String = "PUZZLE_MODE_RCMD"

/**
 * 一个漫游入口：既可能是模式，也可能是场景。
 *
 * [code] 是服务端下发的原始值，模式直接当 mode 用；场景则固定走
 * [NETEASE_ROAMING_SCENE_MODE] 并把 [code] 塞进 subMode。
 */
data class NeteaseRoamingChoice(
    val code: String,
    val title: String,
    val subtitle: String = "",
    val scene: Boolean = false,
    val cascade: String = "",
    val icon: String = ""
) {
    /** 模式与场景可能撞码，UI 选中态用它去重。 */
    val key: String get() = if (scene) "scene:$code" else code

    val wireMode: String get() = if (scene) NETEASE_ROAMING_SCENE_MODE else code

    val wireSubMode: String get() = if (scene) code else ""
}

/**
 * 漫游入口目录。
 *
 * [fallback] 为 true 表示服务端目录接口不可用，当前用的是内置兜底枚举：
 * 网易的运营位接口随时会改结构，兜底不是可选项。
 */
data class NeteaseRoamingCatalog(
    val modes: List<NeteaseRoamingChoice>,
    val scenes: List<NeteaseRoamingChoice>,
    val fallback: Boolean = false
) {
    val choices: List<NeteaseRoamingChoice> get() = modes + scenes

    fun find(key: String?): NeteaseRoamingChoice? =
        key?.let { target -> choices.firstOrNull { it.key == target } }

    fun defaultChoice(): NeteaseRoamingChoice? =
        find(NETEASE_ROAMING_MODE_DEFAULT) ?: modes.firstOrNull() ?: choices.firstOrNull()

    companion object {
        private val FALLBACK_MODES = listOf(
            NeteaseRoamingChoice(NETEASE_ROAMING_MODE_DEFAULT, "默认模式"),
            NeteaseRoamingChoice(NETEASE_ROAMING_MODE_FAMILIAR, "熟悉模式"),
            NeteaseRoamingChoice(NETEASE_ROAMING_MODE_EXPLORE, "探索模式"),
            NeteaseRoamingChoice(NETEASE_ROAMING_MODE_PUZZLE, "拼图模式")
        )

        private val FALLBACK_SCENES = listOf(
            "NIGHT_EMO",
            "EXERCISE",
            "SLEEP_HELP",
            "RELAX",
            "FOCUS",
            "COMMUTE"
        ).map { code -> NeteaseRoamingChoice(code, "", scene = true) }

        fun fallback(): NeteaseRoamingCatalog =
            NeteaseRoamingCatalog(
                modes = FALLBACK_MODES,
                scenes = FALLBACK_SCENES,
                fallback = true
            )
    }
}
