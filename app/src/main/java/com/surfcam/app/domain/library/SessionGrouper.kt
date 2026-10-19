package com.surfcam.app.domain.library

/** Une vidéo de la bibliothèque, réduite à ce qu'il faut pour la ranger. */
data class MediaInfo(
    val key: String,
    /** Début (heure du téléphone, en ms), à la minute près pour les fichiers déjà enregistrés. */
    val startMs: Long,
    val durationMs: Long,
    val kind: Kind,
    /** Numéro du morceau pour une session (1, 2, 3…). */
    val partNumber: Int = 0,
) {
    enum class Kind { PART, FILM, WAVE, MONTAGE }

    val endMs: Long get() = startMs + durationMs
}

/** Une session : un film (assemblé ou non) et ses vagues, qui en sont les chapitres. */
data class SessionGroup(
    val parts: List<MediaInfo>,
    val film: MediaInfo?,
    val waves: List<MediaInfo>,
) {
    private val all: List<MediaInfo> get() = parts + listOfNotNull(film)
    val startMs: Long get() = all.minOf { it.startMs }
    val endMs: Long get() = maxOf(parts.maxOfOrNull { it.endMs } ?: 0L, film?.endMs ?: 0L)

    /** Position d'une vague dans le film, en ms depuis son début (à la minute près pour les anciens fichiers). */
    fun offsetOf(wave: MediaInfo): Long = (wave.startMs - startMs).coerceAtLeast(0L)
}

data class Grouping(val sessions: List<SessionGroup>, val looseWaves: List<MediaInfo>)

/**
 * Range les vidéos : les morceaux consécutifs forment une session (un nouveau numéro 1 ou un trou de plus de 10 minutes
 * en commence une autre), un film assemblé se rattache à la session qui commence à la même heure, et chaque vague
 * tombant pendant une session en devient un chapitre.
 */
object SessionGrouper {
    private const val MAX_GAP_MS = 10 * 60_000L
    private const val FILM_MATCH_MS = 2 * 60_000L
    private const val WAVE_MARGIN_MS = 60_000L

    fun group(items: List<MediaInfo>): Grouping {
        val parts = items.filter { it.kind == MediaInfo.Kind.PART }.sortedBy { it.startMs }
        val films = items.filter { it.kind == MediaInfo.Kind.FILM }.sortedBy { it.startMs }.toMutableList()
        val waves = items.filter { it.kind == MediaInfo.Kind.WAVE }
        val montages = items.filter { it.kind == MediaInfo.Kind.MONTAGE }

        val partGroups = mutableListOf<MutableList<MediaInfo>>()
        for (p in parts) {
            val current = partGroups.lastOrNull()
            val previous = current?.last()
            val startsNew = previous == null || p.partNumber <= 1 || p.startMs - previous.endMs > MAX_GAP_MS
            if (startsNew) partGroups += mutableListOf(p) else current!!.add(p)
        }

        val sessions = mutableListOf<SessionGroup>()
        for (g in partGroups) {
            val start = g.first().startMs
            val film = films.firstOrNull { kotlin.math.abs(it.startMs - start) <= FILM_MATCH_MS }
            if (film != null) films.remove(film)
            sessions += SessionGroup(g, film, emptyList())
        }
        // Un film dont les morceaux ont été supprimés reste une session à lui seul.
        films.forEach { sessions += SessionGroup(emptyList(), it, emptyList()) }

        val loose = mutableListOf<MediaInfo>()
        loose += montages // un film de vagues n'est le chapitre de personne : il reste à part
        val chapters = sessions.map { mutableListOf<MediaInfo>() }
        for (w in waves) {
            val i = sessions.indexOfFirst { w.startMs >= it.startMs - WAVE_MARGIN_MS && w.startMs <= it.endMs + WAVE_MARGIN_MS }
            if (i >= 0) chapters[i] += w else loose += w
        }
        return Grouping(
            sessions.mapIndexed { i, s -> s.copy(waves = chapters[i].sortedBy { it.startMs }) }.sortedByDescending { it.startMs },
            loose.sortedByDescending { it.startMs },
        )
    }
}
