@file:UseSerializers(OffsetSerializer::class)

package com.devoid.keysync.model

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.data.serializers.OffsetSerializer
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.domain.KEYCODE_RMC
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers


@Serializable
sealed class DraggableItem {
    // Runtime screen coordinates, measured after window insets and scaling.
    @kotlinx.serialization.Transient
    var touchCenter: Offset? = null
    @kotlinx.serialization.Transient
    var cancelTouchCenter: Offset? = null

    abstract val id: Int
    abstract var position: Offset
    /** Base home position; follows position on normal drag. Swap moves only position, never this. */
    abstract var anchorPosition: Offset?
    abstract fun copy(id: Int=this.id,position: Offset=this.position) :DraggableItem
@Serializable
data class VariableKey(
    override val id: Int,
    override var position: Offset,
    var keyCode:Int? = null,
    var size: Int,
    // 每键独立触摸模式；null 表示跟随全局 normalBtnTouchMode。
    var touchMode: TouchMode? = null,
    var wheelRadius: Float = 50f,
    override var anchorPosition: Offset? = null,
) : DraggableItem(){
    override fun copy(id: Int, position: Offset): DraggableItem =
        VariableKey(id, position, keyCode, size, touchMode, wheelRadius, anchorPosition)

}

    @Serializable
    data class WASDGroup(
        override val id: Int,
        override var position:Offset,
        var scale: Float=1f,
        // 疾跑档：按住 Shift（疾跑修饰键）时摇杆沿中心方向延长的倍数，>1 时生效。
        var sprintScale: Float = 1.5f,
        var center: Offset = Offset.Zero,
        var w: Offset = Offset.Zero,
        var a: Offset = Offset.Zero,
        var s: Offset = Offset.Zero,
        var d: Offset = Offset.Zero,
        var sprintForwardDistance: Float? = null,
        var sprintSideDistance: Float? = null,
        override var anchorPosition: Offset? = null,
    ) : DraggableItem() {
        override fun copy(id: Int, position: Offset): DraggableItem =
            WASDGroup(id, position, scale, sprintScale, center, w, a, s, d, sprintForwardDistance, sprintSideDistance, anchorPosition)
    }

    @Serializable
    data class FixedKey(
        override val id: Int,
        override var position: Offset,
        @SerialName("itemType")
        val type: DraggableItemType,
        var keyCode: Int,
        var size: Int,
        // 每键独立触摸模式；null 表示跟随全局 normalBtnTouchMode。
        var touchMode: TouchMode? = null,
    var wheelRadius: Float = 50f,
    override var anchorPosition: Offset? = null,
    ) : DraggableItem(){
        override fun copy(id: Int, position: Offset): DraggableItem =
            FixedKey(id, position, type, keyCode, size, touchMode, wheelRadius, anchorPosition)
    }
    @Serializable
    data class CancelableKey(
        override val id: Int,
        override var position: Offset,
        var cancelPosition :Offset,
        @SerialName("itemType")
        val type: DraggableItemType,
        var keyCode: Int?=null,
        var size: Int,
        // 每键独立触摸模式；null 表示跟随全局 cancellableTouchMode。
        var touchMode: TouchMode? = null,
    var wheelRadius: Float = 50f,
    override var anchorPosition: Offset? = null,
    ) : DraggableItem(){
        override fun copy(id: Int, position: Offset): DraggableItem =
            CancelableKey(id, position,cancelPosition, type, keyCode, size, touchMode, wheelRadius, anchorPosition)
    }
}

@Serializable
data class KeyMap(
    val type: KeymapType = KeymapType.DEFAULT,
    val position: Offset,
    val center: Offset? = null,
    val end: Offset? = null,
)

enum class KeymapType {
    DEFAULT,CANCELABLE
}

@Serializable
enum class DraggableItemType {
    // 仍在使用的类型（顺序不可改，序列化按 name 不按 ordinal）：
    //   KEY = 通用「按钮」（自定义按键 + 点击/按住模式），WASD_KEY = 移动摇杆，
    //   SHOOTING_MODE / FIRE / SCOPE = 射击三件套。
    KEY, WASD_KEY, SHOOTING_MODE, FIRE, BAG_MAP, SCOPE,
    // ---- 以下语义类型已废弃，只保留枚举值以兼容已持久化的旧预设数据 ----
    // 跳跃/蹲下/趴下/冲刺/装弹/近战/手雷/治疗/武器/互动/标记等动作键都已统一为
    // 一个「按钮」类型（KEY），不再从 UI 单独创建，addNewItem 会把它们回落到 KEY。
    // movement
    JUMP, CROUCH, PRONE, SPRINT,
    // combat
    RELOAD, MELEE, GRENADE, HEAL,
    // weapon slots
    WEAPON_1, WEAPON_2, WEAPON_3,
    // mouse
    MOUSE_MID,
    // interaction / misc
    INTERACT, PING, EMOTE, INSPECT,
    // 追加项（不改既有项位置）
    BACKPACK, MAP, QUICK_SWITCH, USE, ESC,
    // 通用「按住」键：VariableKey + HOLD 模式，自定义按键，用于探头等按住操作
    HOLD_KEY, WALK_TOGGLE
}

/**
 * Single source of truth for the default physical key of each catalog entry.
 *
 * This used to be duplicated in [com.devoid.keysync.service.FloatingWindowStateManager.addNewItem]
 * and [com.devoid.keysync.data.mapping.MappingPresetRepository.defaultKeyCodeFor]; the two copies
 * drifted apart, which left FIRE/SCOPE bound to the mouse middle button when loaded from a preset
 * (the preset JSON omits their `keyCode`, so the fallback value won). Keep every default here and
 * have both call sites delegate to this function.
 */
fun DraggableItemType.defaultKeyCode(): Int = when (this) {
    // 射击模式切换键默认用反引号 `，可在悬浮窗下拉改为鼠标中键。
    DraggableItemType.SHOOTING_MODE -> KeyEvent.KEYCODE_GRAVE
    DraggableItemType.FIRE -> KEYCODE_LMC
    DraggableItemType.SCOPE -> KEYCODE_RMC
    DraggableItemType.MOUSE_MID -> KEYCODE_MMC
    DraggableItemType.JUMP -> KeyEvent.KEYCODE_SPACE
    DraggableItemType.CROUCH -> KeyEvent.KEYCODE_C
    DraggableItemType.PRONE -> KeyEvent.KEYCODE_Z
    DraggableItemType.WALK_TOGGLE -> KeyEvent.KEYCODE_CAPS_LOCK
    DraggableItemType.SPRINT -> KeyEvent.KEYCODE_SHIFT_LEFT
    DraggableItemType.RELOAD -> KeyEvent.KEYCODE_R
    DraggableItemType.MELEE -> KeyEvent.KEYCODE_V
    DraggableItemType.GRENADE -> KeyEvent.KEYCODE_G
    DraggableItemType.HEAL -> KeyEvent.KEYCODE_H
    DraggableItemType.WEAPON_1 -> KeyEvent.KEYCODE_1
    DraggableItemType.WEAPON_2 -> KeyEvent.KEYCODE_2
    DraggableItemType.WEAPON_3 -> KeyEvent.KEYCODE_3
    DraggableItemType.INTERACT -> KeyEvent.KEYCODE_F
    DraggableItemType.PING -> KeyEvent.KEYCODE_M
    DraggableItemType.EMOTE -> KeyEvent.KEYCODE_B
    DraggableItemType.INSPECT -> KeyEvent.KEYCODE_I
    DraggableItemType.BACKPACK -> KeyEvent.KEYCODE_TAB
    DraggableItemType.MAP -> KeyEvent.KEYCODE_M
    DraggableItemType.QUICK_SWITCH -> KeyEvent.KEYCODE_Q
    DraggableItemType.USE -> KeyEvent.KEYCODE_E
    DraggableItemType.ESC -> KeyEvent.KEYCODE_ESCAPE
    // 可变键 / 摇杆组 / 旧的背包地图合体键 / 按住键：无独立默认键
    DraggableItemType.KEY,
    DraggableItemType.HOLD_KEY,
    DraggableItemType.WASD_KEY,
    DraggableItemType.BAG_MAP -> KeyEvent.KEYCODE_UNKNOWN
}

/** Data-class copy deliberately excludes transient base fields; preserve them for a rebind. */
fun <T : DraggableItem> T.withMeasuredPositionFrom(source: DraggableItem): T = apply {
    touchCenter = source.touchCenter
    cancelTouchCenter = source.cancelTouchCenter
}
