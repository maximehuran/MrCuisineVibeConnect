package app.toque.core.recipes

import java.util.Calendar
import java.util.TimeZone

/** Calendar days avoid skipping every other night when an inexact alarm fires a little early. */
object SyncSchedule {
    const val MIN_CLOCK_MS = 1_735_689_600_000L
    const val RETRY_MS = 15 * 60_000L

    fun dailyDue(now: Long, checkedAt: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        if (now < MIN_CLOCK_MS) return false
        if (checkedAt <= 0 || checkedAt > now) return true
        val a = Calendar.getInstance(zone).apply { timeInMillis = now }
        val b = Calendar.getInstance(zone).apply { timeInMillis = checkedAt }
        return a.get(Calendar.YEAR) != b.get(Calendar.YEAR) || a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)
    }
}
