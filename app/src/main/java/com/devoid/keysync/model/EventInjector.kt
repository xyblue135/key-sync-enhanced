package com.devoid.keysync.model

import androidx.annotation.UiThread
import androidx.compose.ui.geometry.Offset

interface EventInjector {
    @UiThread
    fun injectGesture(pointerID:Int,o1:Offset, o2:Offset)
    fun transFormGesture(pointerID:Int,position:Offset)
    /** 摇杆绝对目标位移；[generation] 大于 0 时，旧代 MOVE 在执行前会被丢弃。 */
    fun transFormGesture(pointerID: Int, position: Offset, generation: Long) {
        transFormGesture(pointerID, position)
    }
    fun releaseGesture(pointerID: Int)
    fun injectPointer(pointerID: Int,o1: Offset,o2: Offset)
    fun injectPointer(pointerID: Int,position: Offset)
    fun updatePointerPosition(pointerID: Int, position:Offset)
    /**
     * 摇杆类绝对目标：带代次。执行时若该触点已经有更新的一代，这条 MOVE 直接丢弃，
     * 避免快速换向时把上一段旧轨迹先走完。
     */
    fun updatePointerPosition(pointerID: Int, position: Offset, generation: Long) {
        updatePointerPosition(pointerID, position)
    }
    /** 标记某触点当前的移动代次；用于让已入队的旧代 MOVE 立即失效。 */
    fun setMoveGeneration(pointerID: Int, generation: Long) {}
    /** 丢弃某触点尚未 flush 的 MOVE 增量（回中抬按前调用，避免尾段把触点拖出界）。 */
    fun cancelPendingMove(pointerID: Int)
    /** 拟人化：控制触点压力/接触面积是否随机（关闭时保持固定值）。 */
    fun setHumanizeContact(enabled: Boolean)
    fun clear()
    fun releasePointer(pointerID: Int)
    suspend fun injected(pointerID: Int):Boolean
}