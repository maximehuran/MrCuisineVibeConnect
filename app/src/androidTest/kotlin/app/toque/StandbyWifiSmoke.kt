package app.toque

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.toque.core.recipes.JsonCatalogHttp
import app.toque.core.recipes.JsonRecipeCatalog
import app.toque.core.recipes.JsonRecipeSource
import app.toque.recipes.JsonSourceSync
import app.toque.ui.MainActivity
import app.toque.ui.screens.SettingsScreen
import app.toque.ui.screens.RecipeSourcesScreen
import java.io.File

/** Installed only on CI emulators. No motor command or hardware mode is used. */
class StandbyWifiSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    private fun waitUntil(test: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!test() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        check(test()) { "Timed out waiting for Android state" }
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) { "CI emulator required" }
            val app = targetContext.applicationContext as ToqueApp
            app.prefs.autoSync = false
            app.prefs.mode = Prefs.Mode.DEMO
            app.prefs.standbyWifiOff = false
            app.wifi.configure()
            // Avoid an unrelated background clock lookup holding the radio during the test.
            Thread.getAllStackTraces().keys.firstOrNull { it.name == "toque-heure" }?.let { it.interrupt(); it.join(60_000) }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitUntil { app.machine.connected }
            val source = JsonRecipeSource("smoke", "CI catalogue", "https://example.org/catalog.json")
            val text = """{"format":"toque-recettes-1","recipes":[{"id":"smoke-recipe","title":"CI recipe","ingredients":[],"steps":[{"text":"No motor operation"}]}]}"""
            val recipes = JsonRecipeCatalog.parse(text, source)
            val now = System.currentTimeMillis()
            runOnMainSync {
                app.prefs.jsonRecipeSources = listOf(source)
                JsonSourceSync.applyResponse(app, source, JsonCatalogHttp.Response(text, "v1", now - 60_000), recipes, true)
                app.recipes.setFavorite(recipes.single().id, true)
                JsonSourceSync.applyResponse(app, source, JsonCatalogHttp.Response(null, "v1", now), null, true)
                check(app.prefs.jsonSourceLastSync(source.id) == now) { "304 did not advance persisted check date" }
                check(app.prefs.jsonSourceResult(source.id).contains("inchangé"))
                check(app.recipes.get(recipes.single().id)?.favorite == true) { "304 altered recipes or favorites" }
                check(JsonSourceSync.due(app, true).isEmpty()) { "Already checked source must not run again tonight" }
                val before = app.prefs.jsonSourceLastSync(source.id)
                app.prefs.guidedRecipe = "ci-guided-recipe"
                try {
                    JsonSourceSync.applyResponse(app, source, JsonCatalogHttp.Response(null, "v1", now + 1), null, true)
                    error("Guided recipe must defer the import")
                } catch (_: IllegalStateException) { }
                check(app.prefs.jsonSourceLastSync(source.id) == before)
                app.prefs.guidedRecipe = ""
                activity.push(SettingsScreen(activity))
                check(texts(activity.current!!.view).any { it.contains("Couper le Wi-Fi") })
            }
            waitForIdleSync()
            screenshot(activity, "settings-wifi.png")
            runOnMainSync {
                activity.push(RecipeSourcesScreen(activity))
                check(texts(activity.current!!.view).any { it.contains("Dernière vérification réussie") })
            }
            waitForIdleSync()
            screenshot(activity, "source-checked-date.png")
            var radio = "not-supported-by-emulator"
            if (targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
                @Suppress("DEPRECATION")
                val wifi = targetContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                check(wifi.setWifiEnabled(true))
                waitUntil { wifi.isWifiEnabled }
                app.prefs.standbyWifiOff = true
                app.wifi.configure()
                app.wifi.standby(true)
                waitUntil { !wifi.isWifiEnabled }
                val night = app.wifi.hold()
                waitUntil { wifi.isWifiEnabled }
                night.close()
                waitUntil { !wifi.isWifiEnabled }
                app.wifi.standby(false)
                waitUntil { wifi.isWifiEnabled }
                radio = "sleep-night-window-wake-ok"
            }
            runOnMainSync { activity.finish() }
            result.putString("stream", "STANDBY_WIFI_SMOKE_OK: api=${Build.VERSION.SDK_INT}; radio=$radio; HTTP304-date-and-favorites=ok\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "STANDBY_WIFI_SMOKE_FAILED: $e\n${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun screenshot(a: Activity, name: String) {
        val image = requireNotNull(uiAutomation.takeScreenshot())
        File(a.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
