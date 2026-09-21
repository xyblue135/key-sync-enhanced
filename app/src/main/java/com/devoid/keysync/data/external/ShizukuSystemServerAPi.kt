package com.devoid.keysync.data.external

import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.domain.EventInjectorImpl
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

class ShizukuSystemServerAPi {
    private val TAG = "ShizukuSystemServerApi"
    private var injectInputEventFunction : Method? = null
    private  var inputManagerInstance:Any? =null

    @Synchronized
    private fun connectInputApi() {
        if (injectInputEventFunction != null && inputManagerInstance != null) {
            return
        }

        val inputManagerBinder: IBinder =
            ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
        val inputManagerStub = Class.forName("android.hardware.input.IInputManager\$Stub")
        val inputManagerClass = Class.forName("android.hardware.input.IInputManager")
        inputManagerInstance =
            inputManagerStub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, inputManagerBinder)
        injectInputEventFunction = inputManagerClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.java
        )
    }

    @Synchronized
    private fun resetInputApi() {
        injectInputEventFunction = null
        inputManagerInstance = null
    }

    private fun injectInputEvent(event: InputEvent) {
        try {
            connectInputApi()
            injectInputEventFunction!!.invoke(inputManagerInstance, event, INJECT_MODE_ASYNC)
        } catch (first: Throwable) {
            // The proxy becomes stale when Shizuku/system_server restarts.
            // Drop the cached proxy so the *next* input re-acquires it. Do not
            // retry the same event here: if the remote side accepted it before
            // the binder failed, replaying a DOWN/UP could duplicate an edge.
            Log.w(TAG, "input proxy failed; next event will reconnect", first)
            resetInputApi()
            throw first
        }
    }

    /**
     * Injection mode passed to `IInputManager.injectInputEvent`.
     *
     * `INJECT_INPUT_EVENT_MODE_ASYNC` (0) returns immediately and lets the
     * InputDispatcher drain events in FIFO order. Keymappers such as 熊猫映射
     * use ASYNC to keep the mouse-aim hot path latency low; the old code used
     * `WAIT_FOR_RESULT` (1), which blocks the injecting thread on every single
     * DOWN/MOVE/UP until the event has been dispatched — enough to make a
     * high-polling-rate mouse feel laggy ("不跟手").
     *
     * Recycle-after-ASYNC is safe here: the event crosses a Shizuku binder, so
     * it is marshalled into a Parcel and the system server works on its own
     * deserialised copy; the local MotionEvent is no longer referenced after
     * the call returns.
     */
    private companion object {
        const val INJECT_MODE_ASYNC = 0
    }

    fun getEventHandler(): EventHandler {
       return EventHandler(object : EventInjectorImpl(){
           override fun inject(event: InputEvent) {
//               Log.d(TAG, "inject: injecting: $event")
               injectInputEvent(event)
           }
       })
    }



}