package com.devoid.keysync.data.remote

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载并安装 GitHub Release 里的 APK。
 *
 * APK 交给系统 DownloadManager 下载（支持 GitHub 的 302 跳转与通知栏进度），
 * 完成后通过 FileProvider 拉起系统安装器，省去手动拷贝安装包。
 */
class ApkInstaller(private val context: Context) {

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val DOWNLOAD_DIR = "updates"
    }

    /** 已授予「安装未知来源应用」权限？ */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 跳转到本应用的「安装未知来源应用」授权页。 */
    fun openInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 下载 APK 并轮询进度，成功后返回本地文件。
     * @param onProgress 0–100（拿不到总大小时不回调）。
     */
    suspend fun download(
        version: String,
        apkUrl: String,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = "KeySync-Enhanced-$version.apk"
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_DIR,
            ).apply { mkdirs() }
            val target = File(dir, fileName)
            // 先下到 .part，完整校验通过后再替换旧包；下载失败不会把上一版可用的包弄丢。
            val part = File(dir, "$fileName.part")
            if (part.exists()) part.delete()

            val dm = context.getSystemService(DownloadManager::class.java)
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("KeySync Enhanced $version")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "$DOWNLOAD_DIR/$fileName.part",
                )
            val id = dm.enqueue(request)
            val startedAt = SystemClock.uptimeMillis()

            while (true) {
                delay(400)
                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                    ?: error("无法查询下载进度")
                cursor.use {
                    if (!it.moveToFirst()) error("下载任务丢失")
                    val status = it.getInt(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    val soFar = it.getLong(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val total = it.getLong(
                        it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    if (total > 0) onProgress(((soFar * 100) / total).toInt().coerceIn(0, 100))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            if (!part.exists()) error("下载完成但文件不存在")
                            // 断流时 DownloadManager 也可能报成功，这里按字节数再确认一次。
                            if (total > 0 && part.length() != total) {
                                error("下载文件不完整（${part.length()}/$total 字节）")
                            }
                            if (target.exists()) target.delete()
                            if (!part.renameTo(target)) error("无法保存安装包")
                            return@runCatching target
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = it.getInt(
                                it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                            )
                            error("下载失败（$reason）")
                        }
                    }
                }
                if (SystemClock.uptimeMillis() - startedAt > 10 * 60_000L) error("下载超时")
            }
            @Suppress("UNREACHABLE_CODE")
            target
        }.onFailure {
            // 失败时清掉半截包，保留上一次成功下载的完整包。
            runCatching {
                File(
                    File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR),
                    "KeySync-Enhanced-$version.apk.part",
                ).delete()
            }
        }
    }

    /** 拉起系统安装器。 */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
