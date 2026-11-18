package app.toque.update

import android.content.Context
import android.content.SharedPreferences
import app.toque.BuildConfig
import app.toque.core.update.Edition
import app.toque.core.update.Editions

object EditionStorage {
    val edition: Edition = Edition.of(BuildConfig.TOQUE_EDITION)
    val databaseName: String get() = edition.database
    val imagesDirectory: String get() = edition.images

    /** Copy only machine/environment settings once. Catalogues, favorites and guided state stay separate. */
    @Suppress("DEPRECATION")
    fun preferences(context: Context): SharedPreferences {
        val target = context.getSharedPreferences(edition.preferences, Context.MODE_MULTI_PROCESS)
        if (edition == Edition.REFERENCE && !target.contains("editionInitialized")) {
            val source = context.getSharedPreferences(Edition.FORK.preferences, Context.MODE_MULTI_PROCESS)
            val edit = target.edit()
            for ((key, value) in source.all) {
                if (key !in Editions.REFERENCE_SETTINGS) continue
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                }
            }
            check(edit.putBoolean("editionInitialized", true).commit())
        }
        return target
    }
}
