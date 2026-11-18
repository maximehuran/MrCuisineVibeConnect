package app.toque.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import app.toque.ToqueApp
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.update.Release
import app.toque.core.update.Releases
import app.toque.core.update.Edition
import app.toque.core.update.Editions
import app.toque.core.update.EditionArtifact
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLException

/**
 * Mises à jour de Toque depuis les versions publiées sur GitHub.
 * Toutes les méthodes sont bloquantes : à appeler hors du fil de l'interface.
 */
class Updater(private val context: Context) {
    class Failure(message: String) : Exception(message)

    private val log = ToqueApp.of(context).log
    private val https = UpdateHttps(context)
    private var artifact: EditionArtifact? = null

    @Suppress("DEPRECATION")
    private val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val installedVersion: String = info.versionName ?: "0"
    @Suppress("DEPRECATION")
    val installedCode: Int = info.versionCode

    /** Dernière version publiée (lève [Failure] avec un message lisible). */
    fun latest(target: Edition = EditionStorage.edition): Release {
        artifact = null
        val c = open(Releases.LATEST_URL, "application/vnd.github+json")
        try {
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw Failure(tr("Aucune version publiée sur GitHub pour l'instant"))
                403, 429 -> throw Failure(tr("GitHub limite les vérifications : réessayez dans une heure"))
                else -> throw Failure(tr("GitHub a répondu %d", code))
            }
            val text = readSmall(c, 1024 * 1024)
            val r = try { Releases.parse(text) } catch (e: IllegalArgumentException) { throw Failure(tr("Réponse GitHub illisible")) }
            val assets = (Json.parse(text).obj()["assets"] as? List<*>).orEmpty()
            val manifest = assets.map { it.obj() }.firstOrNull { it["name"] == "edition-manifest.json" }
            if (manifest != null) {
                val expected = Editions.manifestUrl(r.tag)
                if (manifest["browser_download_url"] != expected) throw Failure(tr("Réponse GitHub illisible"))
                val m = open(expected, "application/json")
                try {
                    if (m.responseCode != 200) throw Failure(tr("GitHub a répondu %d", m.responseCode))
                    artifact = try { Editions.parse(readSmall(m, Editions.MAX_MANIFEST_BYTES), r).getValue(target) }
                    catch (_: IllegalArgumentException) { throw Failure(tr("Réponse GitHub illisible")) }
                } finally { m.disconnect() }
                val a = artifact!!
                return r.copy(apkName = a.name, apkUrl = a.url, apkSize = a.size)
            }
            if (target != Edition.FORK || EditionStorage.edition != Edition.FORK)
                throw Failure(tr("Cette release ne propose pas encore les deux versions."))
            log.write("maj", "dernière version publiée : ${r.version} (installée : $installedVersion)")
            return r
        } catch (e: IOException) {
            throw network(e)
        } finally {
            c.disconnect()
        }
    }

    fun isNewer(r: Release): Boolean = Releases.isNewer(r.version, installedVersion)

    /** Télécharge l'APK de la version et vérifie qu'il s'agit bien d'une version plus récente de Toque. */
    fun download(r: Release, progress: (Int) -> Unit): File {
        val url = r.apkUrl ?: throw Failure(tr("Cette version ne contient pas d'appli à installer"))
        val dir = File(context.cacheDir, "maj")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Toque-${r.version}.apk")
        val c = open(url, "application/octet-stream")
        try {
            if (c.responseCode != 200) throw Failure(tr("Téléchargement refusé (code %d)", c.responseCode))
            val total = if (r.apkSize > 0) r.apkSize else c.contentLength.toLong()
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > Editions.MAX_APK_BYTES) throw Failure(tr("Le fichier téléchargé n'est pas une appli valide"))
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != shown) { shown = pct; progress(pct) }
                        }
                    }
                }
            }
            if (r.apkSize > 0 && done != r.apkSize) {
                file.delete()
                throw Failure(tr("Téléchargement incomplet, réessayez"))
            }
        } catch (e: IOException) {
            file.delete()
            throw network(e)
        } finally {
            c.disconnect()
        }
        verify(file)
        log.write("maj", "téléchargée : ${file.name} (${file.length() / 1024} Ko)")
        return file
    }

    @Suppress("DEPRECATION")
    private fun verify(file: File) {
        val flags = android.content.pm.PackageManager.GET_SIGNATURES or android.content.pm.PackageManager.GET_META_DATA
        val pi = context.packageManager.getPackageArchiveInfo(file.path, flags)
        if (pi == null) { file.delete(); throw Failure(tr("Le fichier téléchargé n'est pas une appli valide")) }
        if (pi.packageName != context.packageName) { file.delete(); throw Failure(tr("Le fichier téléchargé n'est pas Toque")) }
        val a = artifact
        if (a != null) {
            val hash = file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32768)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            if (hash != a.sha256 || pi.versionCode != a.code || pi.versionName != a.version ||
                pi.applicationInfo.metaData?.getString("app.toque.edition") != a.edition.key) {
                file.delete(); throw Failure(tr("Le fichier téléchargé ne correspond pas à la version choisie."))
            }
            if (!Editions.canInstall(installedCode, a.code, EditionStorage.edition, a.edition, a.edition != EditionStorage.edition)) {
                file.delete(); throw Failure(tr("Cette version n'est pas plus récente que celle installée"))
            }
        } else if (pi.versionCode <= installedCode) { file.delete(); throw Failure(tr("Cette version n'est pas plus récente que celle installée")) }
        val current = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        if (pi.signatures?.toSet().isNullOrEmpty() || pi.signatures.toSet() != current.signatures.toSet()) {
            file.delete(); throw Failure(tr("La signature de l'application téléchargée est incorrecte."))
        }
    }

    /**
     * Installe l'APK. Robot rooté : installation silencieuse puis relance de Toque.
     * Sinon : installateur d'Android (une confirmation s'affiche).
     * En cas de succès Android arrête Toque : la fonction ne revient alors pas.
     */
    fun install(apk: File) {
        if (RootShell.available()) installRoot(apk) else installSession(apk)
    }

    private fun installRoot(apk: File) {
        val tmp = "/data/local/tmp/toque-maj.apk"
        val out = "/data/local/tmp/toque-maj.log"
        val copy = RootShell.run("cat '${apk.absolutePath}' > $tmp && chmod 644 $tmp && rm -f $out && echo ok", 60_000)
        if (!copy.out.contains("ok")) throw Failure(tr("Copie de la mise à jour impossible : %s", copy.err.trim()))
        log.write("maj", "installation (root) de ${apk.name}")
        // Lancée en arrière-plan : Android arrête Toque pendant l'installation, puis `am start` le relance.
        val launch = "am start -n ${context.packageName}/${MainActivity::class.java.name}"
        RootShell.run("(pm install -r $tmp > $out 2>&1; sleep 1; $launch > /dev/null 2>&1; rm -f $tmp) < /dev/null > /dev/null 2>&1 &", 10_000)
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(2_000) } catch (_: InterruptedException) { return }
            val result = RootShell.run("cat $out 2>/dev/null", 5_000).out.trim()
            if (result.contains("Failure") || result.contains("Error")) {
                log.write("maj", "échec : $result")
                throw Failure(explain(result))
            }
        }
        throw Failure(tr("L'installation ne répond pas. Redémarrez le robot puis réessayez."))
    }

    private fun installSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            session.openWrite("toque.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            log.write("maj", "installation (installateur Android) de ${apk.name}")
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw Failure(tr("Installation impossible : %s", e.message))
        } finally {
            session.close()
        }
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        return https.open(url, accept, installedVersion)
    }

    private fun readSmall(c: HttpURLConnection, limit: Int): String = c.inputStream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            if (out.size() + n > limit) throw Failure(tr("Réponse GitHub illisible"))
            out.write(buffer, 0, n)
        }
        out.toString("UTF-8")
    }

    private fun network(e: IOException): Failure {
        log.write("maj", "réseau : $e")
        log.crash("maj-réseau", e)
        return when (e) {
            is SSLException -> Failure(tr("Connexion sécurisée à GitHub impossible. Vérifiez la date et l'heure du robot.") + "\n" +
                tr("Si l'horloge est correcte, consultez le journal : le certificat du serveur peut être incompatible."))
            else -> Failure(tr("Pas de connexion à GitHub. Vérifiez le Wi-Fi du robot."))
        }
    }

    companion object {
        /** Écran à prévenir d'un échec signalé par l'installateur d'Android (fil principal). */
        @Volatile var onInstallError: ((String) -> Unit)? = null

        fun explain(result: String): String = when {
            result.contains("UPDATE_INCOMPATIBLE") || result.contains("SIGNATURE") ->
                tr("La version installée n'a pas la même signature. Désinstallez Toque une fois, puis installez la version de GitHub.")
            result.contains("INSUFFICIENT_STORAGE") -> tr("Espace de stockage insuffisant sur le robot")
            result.contains("VERSION_DOWNGRADE") -> tr("Cette version est plus ancienne que celle installée")
            result.contains("OLDER_SDK") -> tr("Cette version n'est pas compatible avec ce robot")
            else -> tr("Installation refusée : %s", result.lines().lastOrNull { it.isNotBlank() } ?: result)
        }
    }
}

/** Résultat de l'installateur d'Android (appareils non rootés). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val log = ToqueApp.of(c).log
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { c.startActivity(confirm) } catch (e: Exception) { log.write("maj", "confirmation impossible : $e") }
            }
            PackageInstaller.STATUS_SUCCESS -> log.write("maj", "installée")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $status"
                log.write("maj", "échec : $message")
                val text = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) tr("Installation annulée") else Updater.explain(message)
                Handler(Looper.getMainLooper()).post { Updater.onInstallError?.invoke(text) }
            }
        }
    }
}

/** Après une mise à jour : on note la nouvelle version et on relance l'écran d'accueil. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = ToqueApp.of(c)
        val version = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Exception) { "?" }
        app.log.write("maj", "Toque mis à jour en version $version")
        try {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
