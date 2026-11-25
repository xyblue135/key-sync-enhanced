package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset

/** Re-anchor sprint transitions so a floating joystick cannot retain a shifted origin. */
internal class JoystickMotion(
    private val sprintBit: Int,
    private val release: (Int) -> Unit,
    private val start: (Int, Offset, Offset) -> Unit,
    private val move: (Int, Offset) -> Unit,
) {
    private var lastMask = 0
    private var lastPointer = -1
    private var lastCenter = Offset.Zero
    private var lastTarget = Offset.Zero

    /** 当前按下的方向位；0 表示没有触点。 */
    @get:Synchronized
    val activeMask: Int get() = lastMask

    /** 最后一次实际注入的落点。 */
    @get:Synchronized
    val target: Offset get() = lastTarget

    /**
     * 返回 true 表示已经把触点移到 [target]；false 表示调用方接管了这次换向，
     * 需要通过 [jitter] 沿弧线补中间点，最后再补 [target]。
     */
    @Synchronized
    fun update(
        pointer: Int,
        mask: Int,
        center: Offset,
        target: Offset,
        sendTarget: Boolean = true,
        reanchorOnSprint: Boolean = true,
        /** 新按下时首个落点；[sendTarget] = false 时用它代替 [target]，由调用方补曲线。 */
        startOffset: Offset = target,
    ): Boolean {
        if (mask == 0) {
            if (lastMask != 0) release(lastPointer)
            reset()
            return true
        }
        if (lastMask == mask && lastPointer == pointer && lastCenter == center && lastTarget == target) return true
        // 只在「疾跑位发生变化」时重新起按：之前只要处于疾跑状态、任何换向都会
        // release + 重新 DOWN，松 A / 加 A 这类普通换向也被当成疾跑切换，游戏会看到
        // 摇杆瞬间回中再按下，表现为顿一下、方向甩到另一侧。普通换向只发 MOVE。
        //
        // [reanchorOnSprint] = false 时，进出疾跑也不 release：交给调用方沿同一方向
        // 从原落点缓慢拉（推杆）到疾跑半径，避免 Shift 一下把触点瞬移到外圈。
        val sprintChanged = reanchorOnSprint && ((lastMask xor mask) and sprintBit) != 0
        val reanchor = lastMask != 0 && (sprintChanged || lastPointer != pointer || lastCenter != center)
        if (reanchor) release(lastPointer)
        val started = lastMask == 0 || reanchor
        if (started) {
            start(pointer, center, if (sendTarget) target else startOffset)
        } else {
            // 方向切换时先补一帧中点：真人的拇指是划过旧方向到新方向，不会瞬移。
            // 只多发一条 MOVE，不引入等待，也不触发 reanchor。
            if (lastMask != mask && sendTarget) {
                val via = (lastTarget + target) / 2f
                if (via != lastTarget && via != target) move(pointer, via)
            }
            if (sendTarget) move(pointer, target)
        }
        lastMask = mask
        lastPointer = pointer
        lastCenter = center
        lastTarget = target
        return sendTarget
    }

    /**
     * Re-issues a MOVE with a fresh [target] for the already-running contact.
     *
     * Used by the humanization jitter loop. Unlike [update] it never re-anchors:
     * while the sprint bit is present [update] treats *every* call as a sprint
     * transition and lifts + re-presses the contact, so a periodic caller makes
     * the game see the stick let go and restart from neutral on each tick — that
     * is the "跑步时向后顿一下" stutter. Jitter must only drag the live contact.
     */
    @Synchronized
    fun jitter(pointer: Int, mask: Int, target: Offset) {
        if (lastMask == 0 || lastMask != mask || lastPointer != pointer) return
        if (lastTarget == target) return
        move(pointer, target)
        lastTarget = target
    }

    /**
     * 过渡的最后一帧：无条件移动到落点。
     *
     * [update] 在 `sendTarget = false` 时已经把 lastTarget 记成终点，
     * 所以 [jitter] 会跳过终点；但曲线必须精确收尾在落点上。
     */
    @Synchronized
    fun finishMove(pointer: Int, mask: Int, target: Offset) {
        if (lastMask == 0 || lastMask != mask || lastPointer != pointer) return
        move(pointer, target)
        lastTarget = target
    }

    // The caller already cancelled contacts when replacing mappings or entering the editor.
    @Synchronized
    fun reset() { lastMask = 0; lastPointer = -1 }
}
