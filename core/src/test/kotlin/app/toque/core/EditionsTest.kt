package app.toque.core

import app.toque.core.json.Json
import app.toque.core.update.Edition
import app.toque.core.update.Editions
import app.toque.core.update.Release
import org.junit.Assert.*
import org.junit.Test

class EditionsTest {
    private val release = Release("v0.5.1.3", "0.5.1.3", "", "", null, null, 0)
    private fun manifest(): MutableMap<String, Any> = mutableMapOf(
        "format" to Editions.FORMAT, "tag" to release.tag, "version_name" to release.version, "version_code" to 10003,
        "editions" to Edition.values().associate { edition ->
            val tag = if (edition == Edition.FORK) release.tag else "reference-${release.tag}"
            val name = if (edition == Edition.FORK) "Toque-${release.version}.apk" else "Toque-reference-${release.version}.apk"
            edition.key to mutableMapOf<String, Any>("name" to name, "size" to 123, "sha256" to "a".repeat(64),
                "url" to "https://github.com/maximehuran/MrCuisineVibeConnect/releases/download/$tag/$name")
        })

    @Test fun variantsKeepExistingForkFilesAndIsolateReferenceFiles() {
        assertEquals("recettes.db", Edition.FORK.database)
        assertEquals("toque", Edition.FORK.preferences)
        assertEquals("images", Edition.FORK.images)
        assertNotEquals(Edition.FORK.database, Edition.REFERENCE.database)
        assertNotEquals(Edition.FORK.preferences, Edition.REFERENCE.preferences)
        assertNotEquals(Edition.FORK.images, Edition.REFERENCE.images)
        assertTrue("requireBowl" in Editions.REFERENCE_SETTINGS)
        for (key in listOf("guidedRecipe", "guidedStep", "jsonRecipeSources", "lastSyncMs", "builtinVersion"))
            assertFalse(key in Editions.REFERENCE_SETTINGS)
    }

    @Test fun sameCodeSwitchWorksInBothDirectionsButNeverDowngrades() {
        for (current in Edition.values()) {
            val other = Edition.values().first { it != current }
            assertTrue(Editions.canInstall(10003, 10003, current, other, true))
            assertFalse(Editions.canInstall(10003, 10003, current, other, false))
            assertFalse(Editions.canInstall(10003, 10003, current, current, true))
            assertFalse(Editions.canInstall(10003, 10002, current, other, true))
            assertTrue(Editions.canInstall(10003, 10004, current, current, false))
        }
        assertFalse(Editions.canSwitch(true, true, ""))
        assertFalse(Editions.canSwitch(true, false, "paused-recipe"))
        assertFalse(Editions.canSwitch(false, false, ""))
        assertTrue(Editions.canSwitch(true, false, ""))
    }

    @Test fun manifestBindsBothVariantsToTheForkAndOneVersionCode() {
        val parsed = Editions.parse(Json.write(manifest()), release)
        assertEquals(2, parsed.size)
        assertEquals(10003, parsed.getValue(Edition.REFERENCE).code)
        assertTrue(parsed.getValue(Edition.REFERENCE).url.contains("/reference-v0.5.1.3/"))
        assertTrue(parsed.getValue(Edition.FORK).name == "Toque-0.5.1.3.apk")
        for (change in listOf("format" to "wrong", "tag" to "v0.5.1.2", "version_code" to 10003.5)) {
            val m = manifest(); m[change.first] = change.second
            try { Editions.parse(Json.write(m), release); fail("Expected rejection") } catch (_: IllegalArgumentException) {}
        }
        val bad = Json.write(manifest()).replace("https://github.com/", "http://github.com/")
        try { Editions.parse(bad, release); fail("Expected HTTPS rejection") } catch (_: IllegalArgumentException) {}
        val wrongSource = Json.write(manifest()).replace("maximehuran/", "other/")
        try { Editions.parse(wrongSource, release); fail("Expected owner rejection") } catch (_: IllegalArgumentException) {}
    }
}
