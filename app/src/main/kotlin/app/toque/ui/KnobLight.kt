package app.toque.ui

import android.os.Process
import app.toque.core.machine.RootShell
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Lumière de la molette pendant la veille : une respiration lente, en blanc.
 *
 * L'anneau a trois LED, vues par Android sous /sys/class/leds : « red » (rouge), « green » (vert)
 * et « blue », qui allume en fait du blanc (SKMC 1200 C3 ; pas de LED bleue). Les mélanges ne se
 * fondent pas (on voit les couleurs alterner) : on n'utilise que le blanc.
 *
 * Un seul processus root fait la boucle (pas une commande `su` par pas). Il note l'état de l'anneau
 * avant de commencer et le remet au réveil, et s'arrête de lui-même si Toque s'arrête.
 */
object KnobLight {
    private const val LEDS = "/sys/class/leds"
    private const val PID_FILE = "/data/local/tmp/toque-knob-light.pid"
    private const val PERIOD_MS = 10_000
    private const val STEP_MS = 100

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-molette").apply { isDaemon = true } }
    @Volatile private var running = false

    /** Luminosités d'une respiration : douce montée, douce descente, jamais tout à fait éteinte. */
    fun breath(periodMs: Int = PERIOD_MS, stepMs: Int = STEP_MS, low: Int = 2, high: Int = 200): List<Int> {
        val n = periodMs / stepMs
        return (0 until n).map { i ->
            val x = (1 - cos(2 * PI * i / n)) / 2
            (low + x.pow(1.8) * (high - low)).roundToInt()
        }
    }

    fun start() {
        if (running) return
        running = true
        worker.execute {
            val app = Process.myPid()
            val values = breath().joinToString(" ")
            val usleep = STEP_MS * 1000
            val script = """
                L=$LEDS
                [ -f $PID_FILE ] && { kill ${'$'}(cat $PID_FILE) 2>/dev/null; usleep 400000; }
                R0=${'$'}(cat ${'$'}L/red/brightness); G0=${'$'}(cat ${'$'}L/green/brightness); W0=${'$'}(cat ${'$'}L/blue/brightness)
                (
                  restore() { echo ${'$'}R0 > ${'$'}L/red/brightness; echo ${'$'}G0 > ${'$'}L/green/brightness; echo ${'$'}W0 > ${'$'}L/blue/brightness; rm -f $PID_FILE; }
                  trap 'restore; exit 0' TERM INT
                  echo 0 > ${'$'}L/red/brightness; echo 0 > ${'$'}L/green/brightness
                  while [ -d /proc/$app ]; do
                    for v in $values; do
                      echo ${'$'}v > ${'$'}L/blue/brightness; usleep $usleep
                      [ -d /proc/$app ] || break
                    done
                  done
                  restore
                ) </dev/null >/dev/null 2>&1 &
                echo ${'$'}! > $PID_FILE
            """.trimIndent()
            RootShell.run(script, 8_000)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        worker.execute {
            RootShell.run("[ -f $PID_FILE ] && kill ${'$'}(cat $PID_FILE) 2>/dev/null; true", 8_000)
        }
    }
}
