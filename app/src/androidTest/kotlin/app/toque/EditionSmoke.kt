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
            if (action == "tls") {
                val https = UpdateHttps(targetContext)
                for (url in listOf("https://api.github.com/repos/Wuki60/MrCuisineVibeConnect/releases/latest",
                    "https://github.com/Wuki60/MrCuisineVibeConnect/releases/download/v0.5.1/Toque-0.5.1.apk")) {
                    val c = https.open(url, "application/octet-stream", "ci")
                    try {
                        check(c.responseCode == 200)
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
