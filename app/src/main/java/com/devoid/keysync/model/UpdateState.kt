package com.devoid.keysync.model

/** 「检查更新」的一次手动查询状态。 */
sealed interface UpdateState {
    /** 尚未查询过。 */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** 已是最新（或远端版本不高于本地）。 */
    data class UpToDate(val current: String) : UpdateState

    /** 远端有更新版本。 */
    data class Available(
        val current: String,
        val latest: String,
        val releaseUrl: String,
    ) : UpdateState

    data class Failed(val message: String) : UpdateState
}
