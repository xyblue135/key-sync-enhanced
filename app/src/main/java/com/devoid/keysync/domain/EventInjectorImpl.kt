package com.devoid.keysync.domain

import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.EventManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private val inputDispatcher = Executors
    .newSingleThreadExecutor { runnable ->
        Thread(runnable, "KeySyncInputInjector").apply { isDaemon = true }
    }
    .asCoroutineDispatcher()

abstract class EventInjectorImpl(private val eventManager: EventManager = EventManagerImpl()) :
    EventInjector {

    // A dedicated single thread drains the queue instead of Dispatchers.Default.
    // The Default pool is shared with the rest of the app and its threads can be
    // contended; for a latency-critical serialised input pipeline a private
    // thread keeps DOWN/MOVE/UP injection from being delayed by unrelated work.
    private val scope = CoroutineScope(inputDispatcher + SupervisorJob())

    // Unlimited buffer + trySend: every public method below is called from the
    // main thread, so enqueuing synchronously is what keeps DOWN / MOVE / UP in
    // the order they were requested. Launching one coroutine per event (the old
    // behaviour) let a UP overtake its own DOWN on the Default dispatcher, which
    // left pointers stuck down and poisoned every later multi-finger gesture.
    private val taskQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // 高频 MOVE 合并。鼠标（尤其游戏鼠标）是 125–1000Hz 事件源，若每条 MOVE 都单独
    // enqueue 一条「反射 invoke + Shizuku binder + 系统注入」，注入吞吐一旦低于事件
    // 频率就会在串行队列里积压，表现为「鼠标停了视角还在转 / 转动明显滞后 / 疾跑时
    // 更卡」。这里把同一触点两次 flush 之间产生的所有位移累加成一条，每批只注入一次。
    private val pendingMoves = PendingMoves()

    abstract fun inject(event: InputEvent)

    init {
        scope.launch {
            for (task in taskQueue) {
                try {
                    task.invoke()
                } catch (t: Throwable) {
                    // A transient Shizuku/binder/reflection failure must not
                    // kill the only queue-draining coroutine. If it dies, the
                    // UNLIMITED channel keeps accepting input forever and the
                    // app appears alive while controls are permanently dead.
                    Log.e(TAG, "input injection task failed; resetting pointer state", t)
                    pendingMoves.clear()
                    try {
                        eventManager.clear()
                    } catch (resetError: Throwable) {
                        Log.e(TAG, "failed to reset pointer state after injection error", resetError)
                    }
                }
            }
        }
    }

    private fun enqueue(coalesce: Boolean = false, task: suspend () -> Unit) {
        if (!coalesce) pendingMoves.seal()
        if (taskQueue.trySend(task).isFailure)
            Log.w(TAG, "input task queue rejected an event")
    }

    override fun injectGesture(pointerID: Int, o1: Offset, o2: Offset) {
        // 摇杆语义：先在摇杆中心（o1）按下，再立即移到方向点（o2）。拖拽式/浮动摇杆
        // 类游戏（三角洲等）读的是触点的位移增量，若直接在方向点 down 就没有位移，角色
        // 原地不动 —— 这正是「按 W/Shift 走不动」的根因。
        //
        // 之前的实现用 ValueAnimator 在注入线程里 withContext(Main) 切主线程起 80ms 动画，
        // 高频变换时和鼠标 MOVE 争抢主线程，导致转视角卡顿。这里改成在注入线程内一步完成
        // 「down(中心) + move(方向点)」，既保住位移语义，又完全不切主线程、不起动画。
        enqueue {
            when (eventManager.addPointer(pointerID, o1.x, o1.y)) {
                -2 -> {
                    // 触点已 down（上一次手势没释放）：直接重定位到方向点。
                    eventManager.updatePointer(pointerID, o2.x, o2.y)
                    dispatch(MotionEvent.ACTION_MOVE)
                }

                -1 -> Unit // pointer id 越界或 16 指上限

                else -> {
                    // 第一步：在摇杆中心 down。
                    val action = eventManager.getPointerAction(pointerID, false)
                    eventManager.createMotionEvent(action)?.let {
                        inject(it)
                        it.recycle()
                    }
                    // 第二步：立即移到方向点，让游戏读到「从中心到方向」的位移增量。
                    if (eventManager.updatePointer(pointerID, o2.x, o2.y)) {
                        dispatch(MotionEvent.ACTION_MOVE)
                    }
                }
            }
        }
    }

    override fun transFormGesture(pointerID: Int, position: Offset) {
        // 摇杆已在 down 状态，直接重定位到新方向点（W→W+Shift 疾跑档、方向切换等）。
        enqueue {
            if (eventManager.updatePointer(pointerID, position.x, position.y)) {
                dispatch(MotionEvent.ACTION_MOVE)
            }
        }
    }

    override fun releaseGesture(pointerID: Int) {
        releasePointer(pointerID)
    }

    override fun injectPointer(pointerID: Int, o1: Offset, o2: Offset) {
        val x = (o1.x.toInt()..o2.x.toInt()).random().toFloat()
        val y = (o1.y.toInt()..o2.y.toInt()).random().toFloat()
        addEventDown(pointerID, x, y)
    }

    override fun injectPointer(pointerID: Int, position: Offset) {
        addEventDown(pointerID, position.x, position.y)
    }

    override fun updatePointerPosition(pointerID: Int, position: Offset) {
        val batch = pendingMoves.add(pointerID, position) ?: return
        enqueue(coalesce = true) {
            val delta = pendingMoves.take(batch) ?: return@enqueue
            if (eventManager.offsetPointer(pointerID, delta)) dispatch(MotionEvent.ACTION_MOVE)
        }
    }

    override fun cancelPendingMove(pointerID: Int) {
        pendingMoves.cancel(pointerID)
    }



    private fun addEventDown(pointerID: Int, x: Float, y: Float) {
        Log.d("KeySyncInput", "DOWN id=$pointerID screen=($x,$y)")
        enqueue {
            when (eventManager.addPointer(pointerID, x, y)) {
                -2 -> {
                    // The pointer is already down (a previous gesture never got
                    // released, or the key re-triggered without an UP). Re-sending
                    // DOWN would encode an invalid ACTION_POINTER_* index and
                    // Android would drop the event together with every other
                    // finger, so just relocate the existing contact instead.
                    eventManager.updatePointer(pointerID, x, y)
                    dispatch(MotionEvent.ACTION_MOVE)
                }

                -1 -> Unit // pointer id out of range or the 16-finger ceiling was hit
                else -> {
                    val action = eventManager.getPointerAction(pointerID, false)
                    val event = eventManager.createMotionEvent(action)
                    event?.let {
                        try {
                            inject(it)
                        } finally {
                            it.recycle()
                        }
                    }
                }
            }
        }
    }

    override fun releasePointer(pointerID: Int) {
        enqueue {
            val action = eventManager.getPointerAction(pointerID, true)
            if (action == -1)
                return@enqueue
            val event = eventManager.createMotionEvent(action)
            event?.let {
                try {
                    inject(it)
                } finally {
                    it.recycle()
                }
            }
            eventManager.removePointer(pointerID)
        }
    }

    override fun clear() {
        // Drop coalesced MOVE data immediately. Otherwise an already queued
        // flush may run after CANCEL and resurrect movement from the old state.
        pendingMoves.clear()
        enqueue {
            val event = eventManager.createMotionEvent(MotionEvent.ACTION_CANCEL)
            event?.let {
                try {
                    inject(it)
                } finally {
                    it.recycle()
                }
            }
            eventManager.clear()
        }
    }

    override suspend fun injected(pointerID: Int): Boolean {
        return eventManager.getPointerAction(pointerID, false) != -1
    }

    /** Builds an event for the current pointer set and pushes it to the input manager. */
    private suspend fun dispatch(action: Int) {
        val event = eventManager.createMotionEvent(action) ?: return
        try {
            inject(event)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TAG = "EventInjectorImpl"
    }
}
