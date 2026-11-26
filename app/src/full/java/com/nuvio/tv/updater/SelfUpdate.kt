package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Self-hosted updates for the Arc + IPTV build: the app reads a small JSON file
 * ([BuildConfig.SELF_UPDATE_URL], set by CI) instead of GitHub releases:
 *
 *     {"versionCode": 200123456, "versionName": "…", "url": "nuvio.apk", "size": 169319354, "notes": "…"}
 *
 * The endpoint is only up now and then: anything other than a valid, newer build on the same
 * host (offline, error page, bad JSON) is "no update", silently.
 */
internal object SelfUpdate {
    val url: String get() = BuildConfig.SELF_UPDATE_URL
    val enabled: Boolean get() = url.isNotBlank()
    /** Checks run at all (Arc's own GitHub check stays off in this build). */
    val checksAllowed: Boolean get() = BuildConfig.UPDATE_CHECK_ENABLED || enabled
    /** Arc skips update checks in debug builds; this build is a debug build that updates itself. */
    val debugBlocked: Boolean get() = BuildConfig.IS_DEBUG_BUILD && !enabled

    suspend fun latest(client: OkHttpClient): com.nuvio.tv.updater.model.AppUpdate = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        val body = try {
            client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (e: java.io.IOException) {
            null
        } ?: throw NoEligibleUpdateException()
        parse(body, url, BuildConfig.VERSION_CODE.toLong()) ?: throw NoEligibleUpdateException()
    }

    /** The update in [body], or null when it isn't a newer build served from [endpoint]'s host. */
    fun parse(body: String, endpoint: String, installedCode: Long): com.nuvio.tv.updater.model.AppUpdate? {
        val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
        val code = json.optLong("versionCode", -1L)
        val apk = json.optString("url").takeIf { it.isNotBlank() } ?: return null
        if (code <= installedCode) return null
        val base = runCatching { java.net.URI(endpoint) }.getOrNull() ?: return null
        val target = runCatching { base.resolve(apk) }.getOrNull() ?: return null
        if (target.host != base.host || target.scheme != "https") return null
        val name = json.optString("versionName").ifBlank { "build $code" }
        return com.nuvio.tv.updater.model.AppUpdate(
            tag = name,
            title = name,
            notes = json.optString("notes"),
            releaseUrl = null,
            assetName = "nuvio-$code.apk",
            assetUrl = target.toString(),
            assetSizeBytes = json.optLong("size", 0L).takeIf { it > 0 },
            versionCode = code
        )
    }
}
