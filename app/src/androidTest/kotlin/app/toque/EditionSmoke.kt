package app.toque

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import app.toque.core.recipes.Recipe
import app.toque.recipes.RecipeRepo
import app.toque.update.EditionStorage
import app.toque.update.UpdateHttps

/** CI emulator only: real Android SQLite/preferences across APK replacements plus HTTPS downloads. */
class EditionSmoke : Instrumentation() {
    private var action = "check"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        action = arguments?.getString("action") ?: "check"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val edition = EditionStorage.edition
            val flags = android.content.pm.PackageManager.GET_META_DATA or android.content.pm.PackageManager.GET_SIGNATURES
            val archive = requireNotNull(targetContext.packageManager.getPackageArchiveInfo(targetContext.applicationInfo.sourceDir, flags))
            check(archive.applicationInfo.metaData?.getString("app.toque.edition") == edition.key) { "APK edition metadata missing" }
            val installed = targetContext.packageManager.getPackageInfo("app.toque", flags)
            check(archive.signatures.toSet() == installed.signatures.toSet()) { "APK certificate lookup differs" }
            val prefs = EditionStorage.preferences(targetContext)
            val repo = RecipeRepo(targetContext)
            val id = "ci-${edition.key}"
            if (action == "seed") {
                repo.replaceAll(listOf(Recipe(id = id, title = "CI ${edition.key}")))
                repo.setFavorite(id, true)
                prefs.edit().putString("ci-edition-marker", edition.key).commit()
            }
            repo.refresh()
            check(repo.get(id)?.favorite == true) { "Favorite did not survive in ${edition.key}" }
            check(prefs.getString("ci-edition-marker", "") == edition.key) { "Preferences crossed editions" }
            check(targetContext.getDatabasePath(edition.database).exists())
            if (action == "standby") {
                check(edition.key == "fork")
                val minutes = prefs.getInt("standbyMinutes", 10)
                val knob = prefs.getBoolean("standbyKnobLight", true)
                prefs.edit().putInt("standbyMinutes", 2).putBoolean("standbyKnobLight", false).commit()
                val activity = startActivitySync(android.content.Intent(targetContext, app.toque.ui.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    runOnMainSync {
                        // Reflection keeps this shared test APK loadable by the unmodified reference build.
                        val standby = activity.javaClass.getDeclaredField("standby").apply { isAccessible = true }.get(activity)
                        val type = standby.javaClass
                        type.getDeclaredField("lastInputMs").apply { isAccessible = true }
                            .setLong(standby, android.os.SystemClock.uptimeMillis() - 121_000L)
                        check(type.getDeclaredMethod("shouldSleep").apply { isAccessible = true }.invoke(standby) == true)
                        type.getDeclaredMethod("sleep").apply { isAccessible = true }.invoke(standby)
                        check(type.getMethod("getAsleep").invoke(standby) == true)
                        check(activity.window.attributes.screenBrightness == 0.02f)
                        check(type.getMethod("onInput").invoke(standby) == true) // Wake gesture is consumed.
                        check(type.getMethod("getAsleep").invoke(standby) == false)
                    }
                } finally {
                    runOnMainSync { activity.finish() }
                    prefs.edit().putInt("standbyMinutes", minutes).putBoolean("standbyKnobLight", knob).commit()
                }
            }
            if (action == "tls") {
                val https = UpdateHttps(targetContext)
                for (url in listOf("https://api.github.com/repos/Wuki60/MrCuisineVibeConnect/releases/latest",
                    "https://github.com/Wuki60/MrCuisineVibeConnect/releases/download/v0.5.1/Toque-0.5.1.apk")) {
                    val api = url.startsWith("https://api.github.com/")
                    val c = https.open(url, if (api) "application/vnd.github+json" else "application/octet-stream", "ci")
                    try {
                        val code = c.responseCode
                        check(code == 200 || (api && code in listOf(403, 429))) { "HTTPS returned $code from $url" }
                        if (code != 200) continue // Shared CI IP rate limiting does not invalidate a successful TLS handshake.
                        c.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            while (input.read(buffer) >= 0) { /* Validate a complete HTTPS response. */ }
                        }
                    } finally { c.disconnect() }
                }
            }
            result.putString("stream", "EDITION_SMOKE_OK:${edition.key}:$action\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "EDITION_SMOKE_FAILED: $error\n${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
