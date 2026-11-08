package com.devoid.keysync.model

/**
 * 功能模式。只影响 UI 入口与宏是否参与键位分发，**不改写任何已保存数据**：
 * 切到 [BASIC] 后宏仍然留在 Profile 里，切回 [FULL] 立即恢复。
 *
 * - [BASIC]：只保留键鼠映射本身，隐藏并停用宏、预设导入导出。
 * - [FULL]：全部功能。
 */
enum class FeatureTier(val storageValue: String) {
    BASIC("basic"),
    FULL("full");

    companion object {
        /**
         * 缺失或无法识别的值一律按 [FULL] 处理：若把读不出的配置当成基础模式，
         * 老用户升级后宏会被静默停用。
         */
        fun fromStorage(raw: String?): FeatureTier =
            values().firstOrNull { it.storageValue == raw } ?: FULL
    }
}
