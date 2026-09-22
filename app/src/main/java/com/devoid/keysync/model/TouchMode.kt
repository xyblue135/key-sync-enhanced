package com.devoid.keysync.model

enum class TouchMode {
    // TAP：点一下触发一次；HOLD：按下持续、松开结束；MIXED：短按/长按混合。
    // WHEEL：按住映射按钮打开轮盘，鼠标在此按钮为圆心的圆形区域选择，松开确认。
    TAP, HOLD, MIXED, WHEEL
}