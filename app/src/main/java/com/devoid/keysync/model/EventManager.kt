package com.devoid.keysync.model

import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset

interface EventManager {
    suspend fun addPointer(id:Int,x:Float,y:Float):Int
    suspend fun updatePointer(id: Int,x: Float,y: Float):Boolean
    suspend fun removePointer(id: Int):Int
    /**
     * Builds a [MotionEvent] that carries **every** pointer that is currently
     * down. The downTime is owned by the manager (it stays constant for the
     * whole gesture), so callers must not try to supply their own.
     */
    suspend fun createMotionEvent(action:Int):MotionEvent?
    suspend fun offsetPointer(id: Int,offset: Offset):Boolean
    suspend fun getPointerAction(pointerId: Int, isUp: Boolean):Int
    suspend fun getPointerLocation(pointerId: Int): Offset?
    suspend fun clear():Boolean
}
