package com.devoid.keysync.model

import androidx.annotation.UiThread
import androidx.compose.ui.geometry.Offset

interface EventInjector {
    @UiThread
    fun injectGesture(pointerID:Int,o1:Offset, o2:Offset)
    fun transFormGesture(pointerID:Int,position:Offset)
    fun releaseGesture(pointerID: Int)
    fun injectPointer(pointerID: Int,o1: Offset,o2: Offset)
    fun injectPointer(pointerID: Int,position: Offset)
    fun updatePointerPosition(pointerID: Int, position:Offset)
    /** 丢弃某触点尚未 flush 的 MOVE 增量（回中抬按前调用，避免尾段把触点拖出界）。 */
    fun cancelPendingMove(pointerID: Int)
    fun clear()
    fun releasePointer(pointerID: Int)
    suspend fun injected(pointerID: Int):Boolean
}