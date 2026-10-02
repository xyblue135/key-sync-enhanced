package com.devoid.keysync.util

import android.view.KeyEvent
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.domain.KEYCODE_RMC
import com.devoid.keysync.domain.KEYCODE_MOUSE_BACK
import com.devoid.keysync.domain.KEYCODE_MOUSE_FORWARD
import com.devoid.keysync.domain.KEYCODE_SCROLL_UP
import com.devoid.keysync.domain.KEYCODE_SCROLL_DOWN
import com.devoid.keysync.domain.KEYCODE_SCROLL_LEFT
import com.devoid.keysync.domain.KEYCODE_SCROLL_RIGHT

fun String.capitalizeFirst():String{
    return this.lowercase().replaceFirstChar { it.titlecaseChar() }
}
fun Int.keyCodeToString():String{
    return when(this){
        KEYCODE_LMC -> "LMB"
        KEYCODE_RMC -> "RMB"
        KEYCODE_MMC -> "MMB"
        KEYCODE_MOUSE_BACK -> "侧键1（后退）"
        KEYCODE_MOUSE_FORWARD -> "侧键2（前进）"
        KEYCODE_SCROLL_UP -> "滚轮向上"
        KEYCODE_SCROLL_DOWN -> "滚轮向下"
        KEYCODE_SCROLL_LEFT -> "滚轮向左"
        KEYCODE_SCROLL_RIGHT -> "滚轮向右"
        KeyEvent.KEYCODE_GRAVE -> "`"
        else-> KeyEvent.keyCodeToString(this).replace("KEYCODE_","").takeIf { it!="1001" }?:"Unknown"
    }
}