package io.nekohasekai.sfa.geoip

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the bundled China-IP rule-set used by sing-box `geoip-cn`.
 *
 * The rule-set ships inside the APK (assets/geoip-cn.srs) so a fresh install
 * always has working data without any network access. It is extracted to the
 * app's private files directory on first use, and can be refreshed either
 * manually or on a schedule from the upstream release.
 *
 * Design notes:
 *  - The bundled asset is the *fallback*. A failed download never deletes the
 *    existing file, so the previously working rule-set stays in place.
 *  - Extraction and download both write to a temporary file and rename it into
 *    position, so a crash mid-write cannot leave a truncated .srs behind.
 *  - A .srs file is only accepted when it starts with the sing-box SRS magic
 *    bytes; an HTML error page or a redirect body is rejected.
 */
object GeoIPRuleSet {

    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/PHCSJC/sing-geoip-cn/main/dist/geoip-cn.srs"

    /** sing-box binary rule-set magic: "SRS". */
    private val SRS_MAGIC = byteArrayOf(0x53, 0x52, 0x53)

    val directory: File
        get() = File(Application.application.filesDir, Path.GEOIP_RULESET_DIR)

    val file: File
        get() = File(directory, Path.GEOIP_RULESET_FILE)

    fun exists(): Boolean = file.isFile && file.length() > 0

    fun size(): Long = if (exists()) file.length() else 0L

    /**
     * Extracts the bundled asset when no usable rule-set is present yet.
     * Safe to call on every service start: it is a no-op once the file exists.
     */
    suspend fun ensureInstalled(): File = withContext(Dispatchers.IO) {
        val target = file
        if (target.isFile && target.length() > 0) return@withContext target

        directory.mkdirs()
        val tmp = File(directory, "${Path.GEOIP_RULESET_FILE}.tmp")
        try {
            Application.application.assets.open(Path.GEOIP_RULESET_ASSET).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!looksLikeSrs(tmp)) {
                error("bundled rule-set is not a valid srs file")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Settings.geoipLastError = ""
        } catch (e: Exception) {
            tmp.delete()
            Settings.geoipLastError = "extract: ${e.message}"
            throw e
        }
        target
    }

    /**
     * Downloads the latest rule-set.
     *
     * On any failure the existing file is left untouched and the error is
     * recorded, so callers can keep running on the previous data.
     */
    suspend fun update(url: String = DEFAULT_URL): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val tmp = File(directory, "${Path.GEOIP_RULESET_FILE}.download")

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SFA-geoip-updater")
                setRequestProperty("Accept", "application/octet-stream")
            }

            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    error("HTTP $code")
                }
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }

            if (!looksLikeSrs(tmp)) {
                tmp.delete()
                error("downloaded file is not a valid srs rule-set")
            }

            val target = file
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }

            Settings.geoipLastUpdate = System.currentTimeMillis()
            Settings.geoipLastVersion = readVersionFromUrl(url) ?: ""
            Settings.geoipLastError = ""
            target.length()
        }.onFailure {
            Settings.geoipLastError = it.message ?: it.javaClass.simpleName
        }
    }

    /** Reads the sibling `version` file; purely informational, never fatal. */
    private fun readVersionFromUrl(url: String): String? = runCatching {
        val versionUrl = url.substringBeforeLast('/') + "/version"
        val connection = (URL(versionUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SFA-geoip-updater")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            connection.inputStream.bufferedReader().readText().trim().take(64)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun looksLikeSrs(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        val head = ByteArray(SRS_MAGIC.size)
        file.inputStream().use { it.read(head) }
        return head.contentEquals(SRS_MAGIC)
    }

    /** True when auto-update is due according to the stored interval. */
    fun isUpdateDue(): Boolean {
        if (!Settings.geoipAutoUpdateEnabled) return false
        if (!exists()) return true
        val intervalMs = Settings.geoipUpdateIntervalHours * 3_600_000L
        if (intervalMs <= 0L) return false
        val last = Settings.geoipLastUpdate
        return last <= 0L || System.currentTimeMillis() - last >= intervalMs
    }
}
