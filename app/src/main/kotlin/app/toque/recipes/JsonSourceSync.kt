package app.toque.recipes

import android.os.Handler
import android.os.Looper
import app.toque.ToqueApp
import app.toque.core.i18n.tr
import app.toque.core.recipes.JsonCatalogHttp
import app.toque.core.recipes.JsonRecipeCatalog
import app.toque.core.recipes.JsonRecipeSource
import app.toque.core.recipes.JsonSourceSyncPolicy
import app.toque.core.recipes.SyncSchedule
import app.toque.core.recipes.Recipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fetches recipe data only; it never starts or adjusts a machine program. */
object JsonSourceSync {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val checkedThisStartup = HashSet<String>()
    var activeSourceId: String? = null
        private set

    fun canChangeCatalog(app: ToqueApp): Boolean = JsonSourceSyncPolicy.canChangeCatalog(
        app.machine.connected, app.machine.snapshot.busy, app.prefs.guidedRecipe)

    /** Called on the main thread. Recheck source configuration and idle state before committing. */
    fun sync(app: ToqueApp, source: JsonRecipeSource, automatic: Boolean = false, done: (Boolean, String) -> Unit = { _, _ -> }) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!canChangeCatalog(app)) {
            done(false, tr("Terminez la recette et attendez que le robot soit au repos."))
            return
        }
        if (!running.compareAndSet(false, true)) {
            done(false, tr("Synchronisation déjà en cours"))
            return
        }
        activeSourceId = source.id
        app.prefs.jsonSourceAttempt(source.id)
        val etag = app.prefs.jsonSourceEtag(source.id)
        Thread {
            try {
                val response = app.wifi.withNetwork { JsonCatalogHttp().fetch(source, etag) }
                val recipes = response.text?.let { JsonRecipeCatalog.parse(it, source) }
                main.post {
                    try {
                        done(true, applyResponse(app, source, response, recipes, automatic))
                    } catch (e: Exception) { error(app, source, e, done) }
                    finally { activeSourceId = null; running.set(false) }
                }
            } catch (e: Exception) {
                main.post { try { error(app, source, e, done) } finally { activeSourceId = null; running.set(false) } }
            }
        }.start()
    }

    /** Main-thread commit shared by HTTP 200 and 304: a successful check always advances the date. */
    internal fun applyResponse(app: ToqueApp, source: JsonRecipeSource, response: JsonCatalogHttp.Response,
                               recipes: List<Recipe>?, automatic: Boolean): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = app.prefs.jsonRecipeSources.firstOrNull { it.id == source.id }
        require(JsonSourceSyncPolicy.stillConfigured(source, current, automatic)) { tr("La source a changé : relancez sa synchronisation.") }
        check(canChangeCatalog(app)) { tr("Terminez la recette et attendez que le robot soit au repos.") }
        if (recipes != null) app.recipes.replaceJsonSource(current!!, recipes)
        val message = if (recipes == null) tr("Catalogue inchangé.") else tr("%d recettes synchronisées.", recipes.size)
        app.prefs.jsonSourceResult(source.id, message, response.checkedAt, response.etag)
        checkedThisStartup += source.id
        app.log.write("source-json", "${source.id}: $message")
        return message
    }

    private fun error(app: ToqueApp, source: JsonRecipeSource, e: Exception, done: (Boolean, String) -> Unit) {
        val message = tr("Synchronisation impossible : %s", e.message ?: e.toString())
        if (app.prefs.jsonRecipeSources.any { it.id == source.id && it.url == source.url }) app.prefs.jsonSourceResult(source.id, message)
        app.log.crash("source-json", e)
        done(false, message)
    }

    /** Called on the main thread by the shared awake/night scheduler. */
    fun due(app: ToqueApp, nightly: Boolean): List<JsonRecipeSource> = app.prefs.jsonRecipeSources.filter {
        it.autoSync && ((!nightly && it.id !in checkedThisStartup) ||
            SyncSchedule.dailyDue(System.currentTimeMillis(), app.prefs.jsonSourceLastSync(it.id)))
    }

    /** Worker-only bridge: keep the nocturnal network window open until the UI commits the result. */
    fun syncAndWait(app: ToqueApp, source: JsonRecipeSource): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        val done = CountDownLatch(1)
        var success = false
        main.post { sync(app, source, automatic = true) { ok, _ -> success = ok; done.countDown() } }
        return done.await(4, TimeUnit.MINUTES) && success
    }
}
