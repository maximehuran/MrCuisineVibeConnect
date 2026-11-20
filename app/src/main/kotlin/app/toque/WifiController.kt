package app.toque

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.network.StandbyWifiPolicy
import java.io.IOException
import java.util.concurrent.Executors

/** Main application process only. Network holds outlive screen standby and overlap safely. */
@Suppress("DEPRECATION")
class WifiController(private val app: ToqueApp) {
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val network = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val policy = StandbyWifiPolicy(app.prefs.standbyWifiOff, app.prefs.wifiStandby)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-wifi").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var started = false

    val asleep: Boolean get() = synchronized(policy) { policy.asleep }
    val associated: Boolean get() = network.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
    val ssid: String get() = wifi.connectionInfo?.ssid.orEmpty().trim('"').takeUnless { it == "<unknown ssid>" }.orEmpty()
    val connected: Boolean get() = network.activeNetworkInfo?.isConnected == true
    val enabled: Boolean get() = wifi.isWifiEnabled
    val connecting: Boolean get() = wifi.wifiState == WifiManager.WIFI_STATE_ENABLING || enabled

    fun start() {
        if (started) return
        started = true
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { reconcile(); notifyState() }
        }, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        })
        reconcile()
    }

    fun standby(value: Boolean) {
        synchronized(policy) { policy.asleep = value; app.prefs.wifiStandby = value }
        reconcile()
    }

    fun configure() {
        synchronized(policy) { policy.cutInStandby = app.prefs.standbyWifiOff }
        reconcile()
    }

    fun observe(listener: () -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners += listener
        listener()
        return { listeners -= listener }
    }

    private fun notifyState() = main.post { listeners.toList().forEach { it() } }

    /** Acquire before starting a transfer; always close, including failures and cancellations. */
    fun hold(): AutoCloseable {
        val token = policy.hold()
        reconcile()
        return AutoCloseable { token.close(); reconcile() }
    }

    fun <T> withNetwork(block: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper())
        val token = hold()
        try {
            val end = SystemClock.elapsedRealtime() + 45_000L
            while (!connected && SystemClock.elapsedRealtime() < end) {
                if (Thread.currentThread().isInterrupted) throw IOException(tr("Synchronisation interrompue."))
                Thread.sleep(250)
            }
            if (!connected) throw IOException(tr("Connexion Wi-Fi indisponible après 45 secondes."))
            return block()
        } finally { token.close() }
    }

    private fun reconcile() {
        worker.execute {
            try {
                val radioOn = wifi.wifiState == WifiManager.WIFI_STATE_ENABLED || wifi.wifiState == WifiManager.WIFI_STATE_ENABLING
                val owned = app.prefs.wifiOwned
                when (policy.action(radioOn, owned)) {
                    StandbyWifiPolicy.Action.DISABLE -> {
                        app.prefs.wifiOwned = true
                        switch(false)
                    }
                    StandbyWifiPolicy.Action.ENABLE -> switch(true)
                    StandbyWifiPolicy.Action.KEEP -> if (owned && radioOn && policy.mayReleaseOwnership()) app.prefs.wifiOwned = false
                }
            } catch (e: Exception) { app.log.write("wifi", "${e.message ?: e}") }
            notifyState()
        }
    }

    private fun switch(on: Boolean) {
        val accepted = try { wifi.setWifiEnabled(on) } catch (_: Exception) { false }
        val ok = accepted || (RootShell.available() && RootShell.run("svc wifi ${if (on) "enable" else "disable"}").ok)
        app.log.write("wifi", if (ok) (if (on) "réactivation demandée" else "coupure en veille demandée") else "changement refusé par Android")
    }
}
