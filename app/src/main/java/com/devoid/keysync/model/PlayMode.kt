package com.devoid.keysync.model

import kotlinx.serialization.Serializable

/**
 * 玩法模式：一组高级功能的开关组合。
 *
 * 简易模式全部关闭，只保留基础按键映射与鼠标；
 * 方案 1、方案 2… 由用户在设置页勾选启用哪些功能，可随时用「+」新增。模式只做运行时门控，
 * 不覆盖各个功能自己的开关，切回其他方案不会丢原来的单项设置。
 */
@Serializable
data class PlayMode(
    val name: String = "高级 A",
    val macros: Boolean = true,
    val assists: Boolean = true,
    val quickMelee: Boolean = true,
    val fCursor: Boolean = true,
    val scrollControl: Boolean = true,
    val discard: Boolean = true,
    val humanization: Boolean = true,
)

val SimplePlayMode = PlayMode(
    name = "简易",
    macros = false,
    assists = false,
    quickMelee = false,
    fCursor = false,
    scrollControl = false,
    discard = false,
    humanization = false,
)

val DefaultPlayModes = listOf(
    SimplePlayMode,
    PlayMode(name = "方案1"),
)
