package com.surfcam.app.domain.naming

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Ce que l'on retrouve dans le nom d'un fichier créé par l'app. */
data class ParsedVideoName(
    val dateTime: ZonedDateTime,
    val isSession: Boolean,
    /** Numéro de la vague, ou numéro de la partie pour une session. */
    val number: Int,
    /** Version recadrée sur le surfeur d'un clip (« … (cadré) »). */
    val reframed: Boolean = false,
    /** Film d'une session assemblée (« … - Session »), par opposition à un morceau de 2 minutes. */
    val isFilm: Boolean = false,
)

/**
 * Noms des fichiers vidéo. La date vient en premier, en ordre année-mois-jour : un explorateur de fichiers qui trie par
 * nom les range alors dans l'ordre chronologique. Pas de « : » (interdit sur les cartes SD en FAT/exFAT) : « 09h38 ».
 *
 *     2026-10-15 09h38 - Vague 3
 *     2026-10-15 09h40 - Session - partie 02
 */
object VideoNaming {

    private val pattern = Regex("""^(\d{4})-(\d{2})-(\d{2}) (\d{2})h(\d{2}) - (?:Vague (\d+)|Session - partie (\d+)|(Session))( \(cadré\))?$""")

    fun clipName(startedAtMs: Long, number: Int, zone: ZoneId = ZoneId.systemDefault()): String =
        "${stamp(startedAtMs, zone)} - Vague $number"

    fun sessionPartName(startedAtMs: Long, part: Int, zone: ZoneId = ZoneId.systemDefault()): String =
        "${stamp(startedAtMs, zone)} - Session - partie ${"%02d".format(part)}"

    /** Nom sans extension ; renvoie null pour un fichier d'une ancienne version ou d'une autre origine. */
    fun parse(fileName: String, zone: ZoneId = ZoneId.systemDefault()): ParsedVideoName? {
        val m = pattern.matchEntire(fileName.removeSuffix(".mp4")) ?: return null
        val (y, mo, d, h, mi) = m.groupValues.drop(1).take(5).map { it.toInt() }
        val isFilm = m.groupValues[8].isNotEmpty()
        val isSession = isFilm || m.groupValues[7].isNotEmpty()
        val number = if (isFilm) 0 else (if (isSession) m.groupValues[7] else m.groupValues[6]).toInt()
        return ParsedVideoName(ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone), isSession, number, m.groupValues[9].isNotEmpty(), isFilm)
    }

    /** Nom du film assemblé d'une session commencée à [startedAtMs] (sans extension). */
    fun filmName(startedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = "${stamp(startedAtMs, zone)} - Session"

    /** Nom du clip recadré correspondant à [baseName] (sans extension). */
    fun reframedName(baseName: String): String = "${baseName.removeSuffix(".mp4")} (cadré)"

    /** Titre court : « Vague 4 », « Session 1 », avec « · cadré » pour une version recadrée. Les anciens noms sont gardés. */
    fun shortTitle(fileName: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val parsed = parse(fileName, zone) ?: return fileName.removeSuffix(".mp4")
        val kind = if (parsed.isFilm) "Session · film" else if (parsed.isSession) "Session ${parsed.number}" else "Vague ${parsed.number}"
        return if (parsed.reframed) "$kind · cadré" else kind
    }

    /** Titre lisible : « Vague 3 · 15 octobre 2026, 9 h 38 ». Les anciens noms sont renvoyés tels quels. */
    fun displayTitle(fileName: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val parsed = parse(fileName, zone) ?: return fileName.removeSuffix(".mp4")
        val t = parsed.dateTime
        val month = t.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)
        val kind = (if (parsed.isFilm) "Session (film)" else if (parsed.isSession) "Session · partie ${parsed.number}" else "Vague ${parsed.number}") + if (parsed.reframed) " (cadré)" else ""
        return "$kind · ${t.dayOfMonth} $month ${t.year}, ${t.hour} h ${"%02d".format(t.minute)}"
    }

    private fun stamp(ms: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return "%04d-%02d-%02d %02dh%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }
}
