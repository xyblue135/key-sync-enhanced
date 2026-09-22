package com.devoid.keysync.domain

import android.os.SystemClock
import android.util.SparseIntArray
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.EventManager

/**
 * Tracks the synthetic touch pointers that are currently "on screen" and builds
 * the [MotionEvent]s that get pushed into the input manager.
 *
 * Multi-touch correctness depends on three rules that the previous version
 * broke:
 *
 * 1. **A gesture shares one downTime.** Every event minted while at least one
 *    pointer is down must report the downTime of the *first* pointer that went
 *    down. Re-stamping downTime on every event makes each event look like the
 *    start of a brand new gesture, so the receiving app tears the fingers apart
 *    and only ever honours one of them.
 * 2. **A pointer that is already down must not be re-declared.** Sending a
 *    second DOWN for an existing pointer produces an ACTION_POINTER_* index that
 *    no longer matches the pointer set and Android throws the event away.
 * 3. **Contact geometry must be non-zero.** A real touchscreen always reports
 *    touchMajor/touchMinor; leaving them at 0 makes some engines drop the point.
 */
class EventManagerImpl : EventManager {
    private val maxPointers = 16 // practical multi-touch ceiling; avoids rejecting common key combinations
    private val pointerIndexMap = SparseIntArray(maxPointers)
    private var activePointerCount = 0
    private val logicalIds = IntArray(maxPointers)
    private val physicalIds = PointerSlots(maxPointers)

    /** downTime shared by every event of the current gesture. 0 == no gesture. */
    private var gestureDownTime = 0L

    private val propsArray = Array(maxPointers) { MotionEvent.PointerProperties() }
    private val coordsArray = Array(maxPointers) { MotionEvent.PointerCoords() }

    private companion object {
        /** Android rejects pointer ids outside this range when building an event. */
        const val MAX_POINTER_ID = 31
        /** Contact size reported for synthetic fingers, in pixels. */
        const val CONTACT_SIZE_PX = 24f
    }

    override suspend fun addPointer(id: Int, x: Float, y: Float): Int {
        var index = pointerIndexMap[id, -1]
        if (index != -1) {//existing event
            coordsArray[index].also { coords ->
                coords.x = x
                coords.y = y
            }
            return -2
        }
        val physicalId = physicalIds.acquire(id) ?: return -1
        index = activePointerCount
        logicalIds[index] = id
        pointerIndexMap.put(id, index)

        // The first pointer of a gesture fixes the downTime for everything that
        // follows until the last finger lifts.
        if (gestureDownTime == 0L)
            gestureDownTime = SystemClock.uptimeMillis()

        // Initialize
        propsArray[index].also { props ->
            props.id = physicalId
            props.toolType = MotionEvent.TOOL_TYPE_FINGER
        }

        coordsArray[index].also { coords ->
            coords.x = x
            coords.y = y
            coords.pressure = 1.0f
            coords.size = 1.0f
            coords.touchMajor = CONTACT_SIZE_PX
            coords.touchMinor = CONTACT_SIZE_PX
            coords.toolMajor = CONTACT_SIZE_PX
            coords.toolMinor = CONTACT_SIZE_PX
            coords.orientation = 0f
        }

        activePointerCount++
        return index
    }

    override suspend fun updatePointer(id: Int, x: Float, y: Float): Boolean {
        val index = pointerIndexMap[id, -1]
        if (index == -1) return false

        coordsArray[index].also { coords ->
            coords.x = x
            coords.y = y
        }
        return true
    }

    override suspend fun removePointer(id: Int): Int {
        val removedIndex = pointerIndexMap[id, -1]
        if (removedIndex == -1) return -1
        for (i in removedIndex until activePointerCount - 1) {
            // Copy next pointer
            propsArray[i].copyFrom(propsArray[i + 1])
            coordsArray[i].copyFrom(coordsArray[i + 1])

            logicalIds[i] = logicalIds[i + 1]
            val nextId = logicalIds[i]
            pointerIndexMap.put(nextId, i)
        }
        pointerIndexMap.delete(id)
        physicalIds.release(id)
        activePointerCount--
        if (activePointerCount == 0)
            gestureDownTime = 0L
        return removedIndex
    }

    override suspend fun createMotionEvent(action: Int): MotionEvent? {
        if (activePointerCount <= 0)
            return null
        // Pass the fixed-size buffers directly instead of sliceArray: obtain()
        // copies exactly `pointerCount` elements via Arrays.copyOf, so handing
        // it the maxPointers-sized arrays is equivalent but avoids two fresh
        // allocations on every MOVE event of a mouse-aim gesture (GC churn on
        // the hottest injection path).
        return MotionEvent.obtain(
            gestureDownTime,
            SystemClock.uptimeMillis(),
            sanitizeAction(action),
            activePointerCount,
            propsArray,
            coordsArray,
            0,
            0,
            1f,
            1f,
            Display.DEFAULT_DISPLAY,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }

    /**
     * ACTION_POINTER_DOWN / ACTION_POINTER_UP encode the affected pointer as an
     * index into the pointer array. If that index ever falls outside the set we
     * are about to send, Android discards the whole event — which silently kills
     * the remaining fingers too. Degrade to a plain MOVE rather than dropping
     * the gesture.
     */
    private fun sanitizeAction(action: Int): Int {
        val masked = action and MotionEvent.ACTION_MASK
        if (masked != MotionEvent.ACTION_POINTER_DOWN && masked != MotionEvent.ACTION_POINTER_UP)
            return action
        val index = (action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr
            MotionEvent.ACTION_POINTER_INDEX_SHIFT
        return if (index in 0 until activePointerCount) action else MotionEvent.ACTION_MOVE
    }

    override suspend fun offsetPointer(id: Int, offset: Offset): Boolean {
        val index = pointerIndexMap[id, -1]
        if (index == -1) return false

        coordsArray[index].also { coords ->
            coords.x += offset.x
            coords.y += offset.y
        }
        return true
    }

    override suspend fun getPointerAction(
        pointerId: Int,
        isUp: Boolean
    ): Int {//make sure to add pointer first
        val index = pointerIndexMap[pointerId, -1]
        if (index == -1) return -1
        return if (activePointerCount <= 1) {
            if (isUp) MotionEvent.ACTION_UP else MotionEvent.ACTION_DOWN
        } else {
            val action =
                if (isUp) MotionEvent.ACTION_POINTER_UP else MotionEvent.ACTION_POINTER_DOWN
            (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) or action
        }
    }

    override suspend fun getPointerLocation(pointerId: Int): Offset? {
        val index = pointerIndexMap[pointerId, -1]
        return if (index == -1) null else {
            val coords = coordsArray[index]
            Offset(coords.x, coords.y)
        }
    }

    override suspend fun clear(): Boolean {
        if (pointerIndexMap.size() == 0)
            return false
        pointerIndexMap.clear()
        physicalIds.clear()
        activePointerCount = 0
        gestureDownTime = 0L
        return true
    }
}
