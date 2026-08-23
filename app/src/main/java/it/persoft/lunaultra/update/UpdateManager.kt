package it.persoft.lunaultra.update

import android.content.Context
import it.persoft.lunaultra.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class DownloadedUpdate(
    val apk: File,
    val commitSha: String,
)

/**
 * Controlla la release mobile del branch e scarica l'APK soltanto quando il commit cambia.
 *
 * Il branch non è scritto nel codice: arriva da `BuildConfig.GIT_BRANCH`, cioè dal ramo che ha
 * prodotto l'APK installato, e resta sostituibile dalle impostazioni. Con un nome fisso nel
 * sorgente ogni ramo nuovo spariva dal radar dell'app già installata, che continuava a
 * interrogare la release del ramo precedente e riferiva "app aggiornata" in buona fede.
 */
class UpdateManager(context: Context) {

    private val appContext = context.applicationContext

    suspend fun downloadIfAvailable(
        currentCommit: String = BuildConfig.GIT_SHA,
        branch: String = BuildConfig.GIT_BRANCH,
    ): Result<DownloadedUpdate?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val release = parseRelease(readText(releaseApi(branch)))
                if (sameCommit(release.commitSha, currentCommit)) return@runCatching null

                val directory = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "luna-${release.commitSha.take(12)}.apk")
                if (!target.isFile || !digestMatches(target, release.sha256)) {
                    download(release.downloadUrl, target, release.sha256)
                }
                DownloadedUpdate(target, release.commitSha)
            }
        }

    private fun readText(url: String): String {
        val connection = open(url)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(url: String, target: File, expectedSha256: String?) {
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        try {
            val connection = open(url)
            val declared = connection.contentLengthLong
            require(declared <= MAX_APK_BYTES || declared < 0) { "APK troppo grande" }
            connection.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_APK_BYTES) { "APK troppo grande" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(part.length() > 0L) { "APK vuoto" }
            require(digestMatches(part, expectedSha256)) { "Firma SHA-256 dell'APK non valida" }
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "Impossibile completare il download" }
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Insta360Luna/${BuildConfig.VERSION_NAME}")
        }

    private fun digestMatches(file: File, expected: String?): Boolean {
        if (expected == null) return file.length() > 0L
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    internal data class ReleaseInfo(
        val commitSha: String,
        val downloadUrl: String,
        val sha256: String?,
    )

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        private const val RELEASES_BY_TAG =
            "https://api.github.com/repos/apatassini/Insta360luna/releases/tags/"

        /**
         * Branch usato quando l'APK non sa da dove viene: compilazione locale, o impostazione
         * lasciata vuota. L'app ha un ramo solo, e questo è il suo nome.
         */
        internal const val FALLBACK_BRANCH = "main"

        /**
         * Il workflow pubblica su `apk-<branch>` con le barre sostituite da trattini: qui si
         * rifà lo stesso calcolo, ed è l'unico punto in cui i due devono restare d'accordo.
         */
        internal fun releaseTag(branch: String): String {
            val name = branch.trim().removePrefix("refs/heads/")
                .takeIf { it.isNotEmpty() && it != "local" }
                ?: FALLBACK_BRANCH
            return "apk-" + name.replace('/', '-')
        }

        internal fun releaseApi(branch: String): String = RELEASES_BY_TAG + releaseTag(branch)

        internal const val MAX_APK_BYTES = 100L * 1024L * 1024L
        internal const val CONNECT_TIMEOUT_MS = 8_000
        internal const val READ_TIMEOUT_MS = 30_000

        internal fun sameCommit(remote: String, local: String): Boolean =
            local != "local" &&
                (remote.equals(local, ignoreCase = true) ||
                    remote.startsWith(local, ignoreCase = true) ||
                    local.startsWith(remote, ignoreCase = true))

        internal fun parseRelease(body: String): ReleaseInfo {
            val root = JSON.parseToJsonElement(body).jsonObject
            val commit = root["target_commitish"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("La release non dichiara il commit")
            val assets = root["assets"]?.jsonArray ?: error("La release non contiene allegati")
            val apk = assets
                .map { it.jsonObject }
                .firstOrNull { asset ->
                    asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true
                }
                ?: error("Nessun APK nella release")
            val url = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("https://") }
                ?: error("Link APK non valido")
            val digest = apk["digest"]?.jsonPrimitive?.contentOrNull
                ?.removePrefix("sha256:")
                ?.takeIf { it.length == 64 }
            return ReleaseInfo(commitSha = commit, downloadUrl = url, sha256 = digest)
        }
    }
}
