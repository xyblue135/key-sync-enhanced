package com.devoid.keysync.data.external

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手动「检查更新」用的 GitHub 查询。
 *
 * 只读公开的 releases / tags 接口，不下载、不自动跳转，也不做后台轮询：
 * 每一次查询都由用户点击触发。
 */
object GitHubReleaseClient {

    const val OWNER = "xyblue135"
    const val REPO = "key-sync-enhanced"
    const val REPO_URL = "https://github.com/$OWNER/$REPO"
    const val RELEASES_URL = "$REPO_URL/releases"

    private const val API_LATEST_RELEASE = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val API_TAGS = "https://api.github.com/repos/$OWNER/$REPO/tags"
    private const val TIMEOUT_MS = 8000

    sealed interface Result {
        data class Success(
            val tag: String,
            val htmlUrl: String,
            /** Release 里第一个 .apk 资源的直链；只有标签没有 Release 时为 null。 */
            val apkUrl: String? = null,
            val notes: String = "",
        ) : Result

        data class Failure(val message: String) : Result
    }

    /**
     * 优先取最新 Release；仓库只打了标签还没有 Release 时回退到 tags 的第一项。
     */
    suspend fun latestTag(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val release = getJson(API_LATEST_RELEASE)?.jsonObject
            val releaseTag = release?.get("tag_name")?.jsonPrimitive?.contentOrNull
            if (!releaseTag.isNullOrBlank()) {
                val htmlUrl = release?.get("html_url")?.jsonPrimitive?.contentOrNull ?: RELEASES_URL
                val assets = (release?.get("assets") as? JsonArray)?.map { it.jsonObject }
                val apkUrl = assets
                    ?.firstOrNull {
                        it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", true) == true
                    }
                    ?.get("browser_download_url")
                    ?.jsonPrimitive?.contentOrNull
                val notes = release?.get("body")?.jsonPrimitive?.contentOrNull.orEmpty()
                return@runCatching Result.Success(releaseTag, htmlUrl, apkUrl, notes)
            }

            val tags = getJson(API_TAGS) as? JsonArray
            val tag = tags?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            if (!tag.isNullOrBlank()) {
                Result.Success(tag, RELEASES_URL)
            } else {
                Result.Failure("仓库暂无发布版本或标签")
            }
        }.getOrElse { throwable ->
            Result.Failure(throwable.message ?: "网络请求失败")
        }
    }

    /** @return 解析后的 JSON；404 返回 null，其余非 2xx 抛错。 */
    private fun getJson(url: String): JsonElement? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "KeySync-Enhanced-Android")
        }
        return try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> null
                in 200..299 -> connection.inputStream.bufferedReader().use {
                    Json.parseToJsonElement(it.readText())
                }
                else -> throw IllegalStateException("GitHub 返回 HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }
}
