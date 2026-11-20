package app.toque.core

import app.toque.core.network.StandbyWifiPolicy
import app.toque.core.network.StandbyWifiPolicy.Action.*
import app.toque.core.recipes.SyncSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class StandbyWifiTest {
    @Test fun screenSleepAndWakeRestoreOnlyOurOwnCut() {
        val p = StandbyWifiPolicy(true, false)
        assertEquals(KEEP, p.action(true, false))
        p.asleep = true
        assertEquals(DISABLE, p.action(true, false))
        assertEquals(KEEP, p.action(false, true))
        p.asleep = false
        assertEquals(ENABLE, p.action(false, true))
        assertEquals(KEEP, p.action(false, false)) // Manually disabled: do not turn it on.
    }

    @Test fun nocturnalWindowClosesAfterAllTransfersEvenOnFailure() {
        val p = StandbyWifiPolicy(true, true)
        val night = p.hold()
        val catalogue = p.hold()
        assertEquals(ENABLE, p.action(false, true))
        assertEquals(KEEP, p.action(true, true))
        catalogue.close()
        catalogue.close() // A repeated cleanup cannot release somebody else's hold.
        assertEquals(KEEP, p.action(true, true))
        night.close()
        assertEquals(DISABLE, p.action(true, true))
    }

    @Test fun wakingDuringSyncAndDisablingOptionLeaveWifiOn() {
        val p = StandbyWifiPolicy(true, true)
        val transfer = p.hold()
        p.asleep = false
        transfer.close()
        assertEquals(KEEP, p.action(true, true))
        assertTrue(p.mayReleaseOwnership())
        p.asleep = true
        p.cutInStandby = false
        assertEquals(ENABLE, p.action(false, true))
        assertEquals(KEEP, p.action(true, false))
    }

    @Test fun processRestartRecoversNightWindowOwnership() {
        val restarted = StandbyWifiPolicy(true, true)
        assertEquals(DISABLE, restarted.action(true, true))
        restarted.asleep = false
        assertEquals(ENABLE, restarted.action(false, true))
    }

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun dailyCheckUsesCalendarDayInsteadOfDriftingAlarmTime() {
        val utc = TimeZone.getTimeZone("UTC")
        val yesterday = at("2026-11-19T03:33:00Z")
        assertTrue(SyncSchedule.dailyDue(at("2026-11-20T03:30:00Z"), yesterday, utc))
        assertFalse(SyncSchedule.dailyDue(at("2026-11-20T20:00:00Z"), at("2026-11-20T03:30:00Z"), utc))
        assertTrue(SyncSchedule.dailyDue(at("2026-11-20T20:00:00Z"), 0, utc))
        assertFalse(SyncSchedule.dailyDue(at("2010-01-01T00:00:00Z"), 0, utc))
        assertTrue(SyncSchedule.dailyDue(at("2026-11-20T03:30:00Z"), at("2026-11-21T03:30:00Z"), utc))
    }

    @Test fun localMidnightAndDaylightSavingStillGetOneCheckPerDay() {
        val paris = TimeZone.getTimeZone("Europe/Paris")
        assertTrue(SyncSchedule.dailyDue(at("2026-11-20T23:30:00Z"), at("2026-11-20T21:00:00Z"), paris))
        assertTrue(SyncSchedule.dailyDue(at("2026-10-25T02:30:00Z"), at("2026-10-24T01:30:00Z"), paris))
    }
}
