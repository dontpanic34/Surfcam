package com.surfcam.app.data.library

import android.content.Context

/** Les vagues mises en favori (étoile), retenues par nom de fichier. */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    /** Met ou retire l'étoile ; renvoie l'ensemble à jour. */
    fun toggle(name: String): Set<String> {
        val now = all().toMutableSet()
        if (!now.add(name)) now.remove(name)
        prefs.edit().putStringSet(KEY, now).apply()
        return now
    }

    fun addAll(names: Collection<String>): Set<String> {
        val now = all() + names
        prefs.edit().putStringSet(KEY, now).apply()
        return now
    }

    fun remove(names: Collection<String>): Set<String> {
        val now = all() - names.toSet()
        prefs.edit().putStringSet(KEY, now).apply()
        return now
    }

    private companion object {
        const val KEY = "names"
    }
}
