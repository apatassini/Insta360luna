package it.persoft.lunaultra.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
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
    /** Quando la release è stata pubblicata: è la data che si mostra al posto del commit. */
    val publishedAtMs: Long? = null,
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

    /**
     * Avanzamento dello scaricamento, in byte.
     *
     * [total] è negativo quando il server non dichiara la dimensione: succede, e allora
     * l'unica cosa onesta da mostrare è quanti megabyte sono arrivati, non una percentuale
     * inventata su un totale che non si conosce.
     */
    fun interface DownloadProgress {
        fun onBytes(downloaded: Long, total: Long)
    }

    suspend fun downloadIfAvailable(
        currentCommit: String = BuildConfig.GIT_SHA,
        branch: String = BuildConfig.GIT_BRANCH,
        channel: UpdateChannel = UpdateChannel.PERSOFT,
        progress: DownloadProgress? = null,
    ): Result<DownloadedUpdate?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val release = when (channel) {
                    // Il manifest del sito non va mai in cache per contratto (`.htaccess` dice
                    // no-store), ma la coda mai vista costa nulla ed elimina anche le cache dei
                    // proxy mobili, che quel contratto non lo hanno firmato.
                    UpdateChannel.PERSOFT -> parsePersoftManifest(readText(cacheBusted(PERSOFT_MANIFEST)))
                    UpdateChannel.GITHUB -> parseRelease(readText(releaseApi(branch)))
                }
                val giaAggiornato = when (channel) {
                    // Il sito dichiara la versione, non il commit: il confronto è fra numeri di
                    // build, lo stesso che farà Android al momento di installare.
                    UpdateChannel.PERSOFT -> (release.versionCode ?: 0L) <= installedVersionCode()
                    UpdateChannel.GITHUB -> sameCommit(release.commitSha, currentCommit)
                }
                if (giaAggiornato) return@runCatching null

                val directory = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "luna-${release.commitSha.take(12)}.apk")
                if (!target.isFile || !digestMatches(target, release.sha256)) {
                    download(release.downloadUrl, target, release.sha256, progress)
                }
                refusal(target)?.let {
                    target.delete()
                    error(it)
                }
                DownloadedUpdate(target, release.commitSha, release.publishedAtMs)
            }
        }

    private fun readText(url: String): String {
        val connection = open(url)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Scarica l'APK e non lo accetta finché i byte non corrispondono all'impronta pubblicata.
     *
     * Due tentativi, e il secondo non è scaramanzia. Gli allegati delle release si scaricano da
     * `releases/download/<tag>/<nome>`, un indirizzo che la rete di distribuzione di GitHub
     * tiene in cache; il nostro workflow riusa sempre lo stesso tag, e finché ha riusato anche
     * lo stesso nome di file quell'indirizzo è rimasto identico da una build all'altra. Le
     * informazioni della release arrivano invece da `api.github.com`, che in cache non ci va:
     * così poteva capitare che l'app leggesse il commit e l'impronta nuovi e si vedesse
     * consegnare i byte della build precedente. L'impronta non tornava e l'aggiornamento si
     * fermava — giustamente, ma senza via d'uscita, perché ritentare dava di nuovo la copia in
     * cache. Il secondo tentativo chiede espressamente byte freschi. Se non torna nemmeno
     * quello, allora il file pubblicato è davvero un altro e fermarsi è la cosa giusta.
     */
    private fun download(
        url: String,
        target: File,
        expectedSha256: String?,
        progress: DownloadProgress? = null,
    ) {
        val part = File(target.parentFile, "${target.name}.part")
        try {
            for (attempt in 0..1) {
                part.delete()
                fetch(if (attempt == 0) url else cacheBusted(url), part, progress, fresh = attempt > 0)
                require(part.length() > 0L) { "APK vuoto" }
                if (digestMatches(part, expectedSha256)) {
                    if (target.exists()) target.delete()
                    check(part.renameTo(target)) { "Impossibile completare il download" }
                    return
                }
            }
            error("L'APK scaricato non corrisponde a quello pubblicato: riprova fra qualche minuto")
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun fetch(url: String, part: File, progress: DownloadProgress?, fresh: Boolean) {
        val connection = open(url, fresh)
        val declared = connection.contentLengthLong
        require(declared <= MAX_APK_BYTES || declared < 0) { "APK troppo grande" }
        connection.inputStream.use { input ->
            part.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                var lastReported = 0L
                progress?.onBytes(0L, declared)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_APK_BYTES) { "APK troppo grande" }
                    output.write(buffer, 0, read)
                    // Un avviso ogni 64 KB: abbastanza fitto perché la barra si muova,
                    // abbastanza rado da non ricomporre la schermata a ogni buffer.
                    if (total - lastReported >= PROGRESS_STEP_BYTES) {
                        lastReported = total
                        progress?.onBytes(total, declared)
                    }
                }
                progress?.onBytes(total, declared)
            }
        }
    }

    private fun open(url: String, fresh: Boolean = false): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Insta360Luna/${BuildConfig.VERSION_NAME}")
            if (fresh) {
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
                setRequestProperty("Pragma", "no-cache")
            }
        }

    /**
     * È davvero un aggiornamento di questa app, quello che si è appena scaricato?
     *
     * Android non installa sopra un pacchetto firmato con una chiave diversa dalla propria, e
     * se ne accorge a installazione avviata: lo dice a modo suo — «firma non valida» — senza
     * far capire da dove venga il problema né cosa si possa fare. La stessa verifica qui si fa
     * prima, sul file scaricato, dove c'è ancora modo di spiegarsi.
     *
     * Si controllano tre cose, e ognuna corrisponde a un modo diverso di sbagliare pacchetto:
     * il nome del pacchetto (è un'altra app), il numero di build (è una copia vecchia — capita
     * quando una cache consegna i byte di ieri sotto l'indirizzo di oggi) e il certificato di
     * firma (è la stessa app compilata con un'altra chiave, e lì non c'è aggiornamento che
     * tenga: va disinstallata).
     *
     * Torna il motivo del rifiuto, o `null` se il pacchetto è buono.
     */
    private fun refusal(apk: File): String? {
        val pm = appContext.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, signingFlags())
            ?: return "il file scaricato non è un APK leggibile"
        // Su parecchie versioni le firme si leggono solo se il pacchetto sa da quale file
        // viene, e getPackageArchiveInfo quel campo non lo compila da sé.
        archive.applicationInfo?.let {
            it.sourceDir = apk.path
            it.publicSourceDir = apk.path
        }
        if (archive.packageName != appContext.packageName) {
            return "il file scaricato è un'altra app (${archive.packageName})"
        }

        val installed = runCatching { pm.getPackageInfo(appContext.packageName, signingFlags()) }
            .getOrNull()
            ?: return null

        val mineCode = versionCode(installed)
        val theirsCode = versionCode(archive)
        if (theirsCode < mineCode) {
            return "l'APK scaricato è più vecchio di quello installato (build $theirsCode " +
                "contro $mineCode): la release ha risposto con una copia vecchia, riprova fra " +
                "qualche minuto"
        }

        val mine = certificates(installed)
        val theirs = certificates(archive)
        if (mine.isEmpty() || theirs.isEmpty()) return null
        if (mine.any { it in theirs }) return null
        return "l'aggiornamento è firmato con una chiave diversa da quella dell'app installata: " +
            "Android non lo installa sopra. Disinstalla l'app una volta sola e reinstallala — " +
            "da lì in avanti gli aggiornamenti tornano a installarsi da soli"
    }

    /** L'impronta di ogni certificato con cui il pacchetto è firmato, comprese le rotazioni. */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        return signatures.orEmpty().filterNotNull().mapTo(HashSet()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
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
        val publishedAtMs: Long? = null,
        /** Solo per il canale Persoft: il manifest del sito dichiara la versione, non il commit. */
        val versionCode: Long? = null,
        val versionName: String? = null,
        val note: String? = null,
    )

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        private const val RELEASES_BY_TAG =
            "https://api.github.com/repos/apatassini/Insta360luna/releases/tags/"

        /**
         * Il canale di distribuzione: una cartella sul sito con l'APK e un manifest che ne
         * dichiara versione e impronta. È lo stesso modello di ViewerImage per Android.
         */
        const val PERSOFT_MANIFEST = "https://www.persoft.it/lunaultra/aggiornamento.txt"

        /**
         * Il manifest del sito, negli stessi cinque campi di ViewerImage.
         *
         * Il commit qui non c'è e non serve: a dire se una build è più recente basta il
         * `versionCode`, che è anche il solo numero che Android guarda quando decide se
         * installare sopra. Si tiene comunque un `commitSha` sintetico perché è quello che dà
         * il nome al file scaricato, e due versioni diverse devono avere due nomi diversi.
         */
        internal fun parsePersoftManifest(body: String): ReleaseInfo {
            val root = JSON.parseToJsonElement(body).jsonObject
            val versionCode = root["versionCode"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: error("Il manifest non dichiara la versione")
            val url = root["apkUrl"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("https://") }
                ?: error("Link APK non valido")
            val versionName = root["versionName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val digest = root["sha256"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length == 64 }
                ?: error("Il manifest non dichiara l'impronta SHA-256")
            val note = root["note"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            return ReleaseInfo(
                commitSha = "persoft-$versionCode",
                downloadUrl = url,
                sha256 = digest,
                versionCode = versionCode,
                versionName = versionName,
                note = note,
            )
        }

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

        /**
         * Lo stesso indirizzo, con una coda che nessuna cache ha mai visto.
         *
         * Le intestazioni `no-cache` chiedono per bene, ma una rete di distribuzione può
         * ignorarle; un indirizzo mai richiesto prima non può essere in cache per definizione.
         */
        internal fun cacheBusted(url: String): String =
            url + (if (url.contains('?')) "&" else "?") + "fresh=" + System.currentTimeMillis()

        internal const val MAX_APK_BYTES = 100L * 1024L * 1024L
        private const val PROGRESS_STEP_BYTES = 64L * 1024L
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
            val published = root["published_at"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            return ReleaseInfo(
                commitSha = commit,
                downloadUrl = url,
                sha256 = digest,
                publishedAtMs = published,
            )
        }
    }
}
