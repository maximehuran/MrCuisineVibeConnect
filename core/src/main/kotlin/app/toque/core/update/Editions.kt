package app.toque.core.update

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.obj

/** Separate files, identical Android package/signature; the installed build chooses its files. */
enum class Edition(val key: String, val database: String, val preferences: String, val images: String) {
    FORK("fork", "recettes.db", "toque", "images"),
    REFERENCE("reference", "recettes-reference.db", "toque-reference", "images-reference");

    val label: String get() = when (this) {
        FORK -> tr("Fork Maxime")
        REFERENCE -> tr("Référence Wuki60")
    }

    companion object {
        fun of(key: String): Edition = values().firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown edition")
    }
}

data class EditionArtifact(val edition: Edition, val version: String, val code: Int, val url: String,
                           val name: String, val size: Long, val sha256: String)

/** Authenticated release metadata. Equal version codes are allowed only for an explicit edition change. */
object Editions {
    const val FORMAT = "toque-editions-1"
    const val MAX_APK_BYTES = 20 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 32 * 1024
    val REFERENCE_SETTINGS = setOf("mode", "language", "twinHost", "twinPort", "serialDevice", "vendorPackage",
        "requireBowl", "hotRamp", "chainSteps", "sound", "timeZone", "lastZone", "bootLaunch", "welcomeDone",
        "knobLeft", "knobRight", "knobPress")

    fun canSwitch(connected: Boolean, busy: Boolean, guidedRecipe: String): Boolean = connected && !busy && guidedRecipe.isBlank()

    fun canInstall(installedCode: Int, candidateCode: Int, current: Edition, target: Edition, switching: Boolean): Boolean =
        candidateCode > installedCode || (switching && current != target && candidateCode == installedCode)

    fun manifestUrl(tag: String): String {
        require(Regex("v\\d+\\.\\d+\\.\\d+\\.\\d+").matches(tag))
        return "https://github.com/${Releases.REPO}/releases/download/$tag/edition-manifest.json"
    }

    fun parse(text: String, release: Release): Map<Edition, EditionArtifact> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES)
        // Metadata is flat and small; reject excessive nesting before the recursive JSON reader.
        require(text.count { it == '{' || it == '[' } <= 24)
        val root = Json.parse(text).obj()
        require(root["format"] == FORMAT && root["tag"] == release.tag && root["version_name"] == release.version)
        manifestUrl(release.tag)
        val code = root["version_code"] as? Number ?: throw IllegalArgumentException()
        require(code.toDouble() == code.toInt().toDouble() && code.toInt() in 1..2_100_000_000)
        val editions = root["editions"].obj()
        require(editions.keys == Edition.values().map { it.key }.toSet())
        return Edition.values().associateWith { edition ->
            val m = editions[edition.key].obj()
            val name = if (edition == Edition.FORK) "Toque-${release.version}.apk" else "Toque-reference-${release.version}.apk"
            val tag = if (edition == Edition.FORK) release.tag else "reference-${release.tag}"
            val url = "https://github.com/${Releases.REPO}/releases/download/$tag/$name"
            val size = m["size"] as? Number ?: throw IllegalArgumentException()
            val sha = m["sha256"] as? String ?: ""
            require(m["url"] == url && m["name"] == name && Regex("[a-f0-9]{64}").matches(sha))
            require(size.toDouble() == size.toLong().toDouble() && size.toLong() in 1..MAX_APK_BYTES.toLong())
            EditionArtifact(edition, release.version, code.toInt(), url, name, size.toLong(), sha)
        }
    }
}
