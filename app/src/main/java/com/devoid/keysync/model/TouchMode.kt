package com.devoid.keysync.model

enum class TouchMode {
    // TAP：点一下触发一次；HOLD：按下持续、松开结束；MIXED：短按/长按混合。
    // WHEEL：轮盘——按住键时在按键位置注入一个触点，鼠标滚轮让触点绕中心
    // 360° 旋转（选择药品等），松开确认。仅每键 touchMode 有效，全局模式不用。
    TAP, HOLD, MIXED, WHEEL
}