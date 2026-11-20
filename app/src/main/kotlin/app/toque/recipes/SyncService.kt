package app.toque.recipes

import android.app.AlarmManager
import android.app.IntentService
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import app.toque.Prefs
import app.toque.TimeSync
import app.toque.ToqueApp
import app.toque.core.recipes.SyncSchedule
import app.toque.core.recipes.JsonRecipeSource
import app.toque.core.i18n.tr
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import java.util.Calendar
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Shared recipe window: background work never wakes the screen, and always releases Wi-Fi. */
@Suppress("DEPRECATION")
class SyncService : IntentService("toque-synchro") {
    override fun onHandleIntent(intent: Intent?) {
        val app = ToqueApp.of(this)
        val nightly = intent?.getBooleanExtra("nightly", false) == true
        val attempt = intent?.getIntExtra("attempt", 0) ?: 0
        val lock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toque:recipe-sync")
        lock.acquire(35 * 60_000L)
        releaseHandoff()
        running = true
        val worker = Thread.currentThread()
        val timeout = Runnable { worker.interrupt() }
        main.postDelayed(timeout, 30 * 60_000L)
        var retry = false
        var pendingSources = emptyList<JsonRecipeSource>()
        val began = System.currentTimeMillis()
        try {
            if (!app.prefs.autoSync && app.prefs.jsonRecipeSources.none { it.autoSync }) return
            if (!awaitIdle(app)) {
                app.log.write("synchro-auto", "reportée : machine occupée, recette guidée ouverte ou base indisponible")
                retry = true
                return
            }
            app.recipes.refresh() // A nocturnal service can start without ever opening the activity.
            val sources = onMain { JsonSourceSync.due(app, nightly) }
            pendingSources = sources
            val lidl = app.prefs.autoSync && SyncSchedule.dailyDue(System.currentTimeMillis(), app.prefs.lastSyncMs)
            if (sources.isEmpty() && !lidl) return
            sources.forEach { app.prefs.jsonSourceAttempt(it.id) }
            app.wifi.withNetwork {
                try { TimeSync(app, app.prefs, app.log).run() } catch (e: Exception) { if (e is InterruptedException) throw e; app.log.write("heure", e.message ?: e.toString()) }
                if (System.currentTimeMillis() < SyncSchedule.MIN_CLOCK_MS) { retry = true; return@withNetwork }
                // Check personal sources first, before a potentially long Lidl catalogue download.
                for (source in sources) {
                    if (!onMain { JsonSourceSync.canChangeCatalog(app) }) { retry = true; break }
                    if (!JsonSourceSync.syncAndWait(app, source)) retry = true
                }
                if (lidl && onMain { JsonSourceSync.canChangeCatalog(app) }) {
                    if (!OnlineSync(app, app.prefs, app.recipes, app.log).sync().ok) retry = true
                } else if (lidl) retry = true
            }
        } catch (e: Exception) {
            retry = true
            app.log.write("synchro-auto", "échec : ${e.message ?: e}")
            for (source in pendingSources) {
                if (app.prefs.jsonSourceLastSync(source.id) < began &&
                    app.prefs.jsonRecipeSources.any { it.id == source.id && it.url == source.url })
                    app.prefs.jsonSourceResult(source.id, tr("Synchronisation impossible : %s", e.message ?: e.toString()))
            }
        } finally {
            main.removeCallbacks(timeout)
            Thread.interrupted()
            running = false
            if (lock.isHeld) lock.release()
            if (nightly) {
                schedule(this)
                if (retry && attempt < 2) scheduleRetry(this, attempt + 1)
            }
        }
    }

    private fun awaitIdle(app: ToqueApp): Boolean {
        onMain { app.machine.connect() }
        val end = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < end) {
            val state = onMain {
                when {
                    app.machine.snapshot.busy || app.prefs.guidedRecipe.isNotBlank() -> -1
                    app.machine.connected && (app.machine.snapshot.hasSample || app.prefs.mode == Prefs.Mode.DEMO) -> 1
                    else -> 0
                }
            }
            if (state != 0) return state == 1
            Thread.sleep(100)
        }
        return false
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        main.post(task)
        return task.get(15, TimeUnit.SECONDS)
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var running = false
        private var started = false
        private var lastAttempt = -SyncSchedule.RETRY_MS
        private var scheduledDay = ""
        private var handoff: PowerManager.WakeLock? = null

        @Synchronized private fun releaseHandoff() {
            handoff?.let { if (it.isHeld) it.release() }
            handoff = null
        }

        /** Preserve CPU between an alarm receiver returning and IntentService starting. */
        @Synchronized fun enqueue(c: Context, nightly: Boolean, attempt: Int = 0) {
            releaseHandoff()
            handoff = (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toque:sync-handoff").apply { acquire(60_000L) }
            try {
                c.startService(Intent(c, SyncService::class.java).putExtra("nightly", nightly).putExtra("attempt", attempt))
            } catch (e: Exception) { releaseHandoff(); throw e }
        }

        fun startScheduler(app: ToqueApp) {
            if (started) return
            started = true
            main.postDelayed(object : Runnable {
                override fun run() {
                    try {
                        val now = System.currentTimeMillis()
                        if (now >= SyncSchedule.MIN_CLOCK_MS) {
                            val day = Calendar.getInstance().let { "${it.timeZone.id}:${it.get(Calendar.YEAR)}:${it.get(Calendar.DAY_OF_YEAR)}" } +
                                ":${app.prefs.autoSync}:" + app.prefs.jsonRecipeSources.filter { it.autoSync }.joinToString { it.id }
                            if (day != scheduledDay) { schedule(app); scheduledDay = day }
                            if (!running && !app.wifi.asleep && app.wifi.connected && JsonSourceSync.canChangeCatalog(app) &&
                                SystemClock.elapsedRealtime() - lastAttempt >= SyncSchedule.RETRY_MS &&
                                (JsonSourceSync.due(app, false).isNotEmpty() || (app.prefs.autoSync && SyncSchedule.dailyDue(now, app.prefs.lastSyncMs)))) {
                                lastAttempt = SystemClock.elapsedRealtime()
                                enqueue(app, false)
                            }
                        }
                    } catch (e: Exception) { app.log.write("synchro-auto", e.message ?: e.toString()) }
                    finally { main.postDelayed(this, 60_000L) }
                }
            }, 30_000L)
        }

        fun schedule(c: Context) {
            if (System.currentTimeMillis() < SyncSchedule.MIN_CLOCK_MS) return
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(c, 0, Intent(c, SyncAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            val prefs = Prefs(c)
            if (!prefs.autoSync && prefs.jsonRecipeSources.none { it.autoSync }) { am.cancel(pi); return }
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }
            am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 5 * 60_000L, pi)
        }

        private fun scheduleRetry(c: Context, attempt: Int) {
            val intent = Intent(c, SyncAlarm::class.java).putExtra("attempt", attempt)
            val pi = PendingIntent.getBroadcast(c, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + SyncSchedule.RETRY_MS, pi)
        }
    }
}

class SyncAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) = SyncService.enqueue(c, true, intent.getIntExtra("attempt", 0))
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        SyncService.schedule(c)
        c.startService(Intent(c, MachineService::class.java))
        if (Prefs(c).bootLaunch) {
            val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(open)
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 20_000,
                PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }
}
