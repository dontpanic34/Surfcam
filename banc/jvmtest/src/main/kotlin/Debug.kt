import com.surfcam.app.domain.tracking.*
import java.io.File

fun main(args: Array<String>) {
    val from = args[1].toLong() * 1_000_000; val to = args[2].toLong() * 1_000_000
    val lines = File(args[0]).readLines().map { it.split(",") }
    val byTs = lines.groupBy { it[0].toLong() }.toSortedMap()
    val tracker = SurferTracker(TrackerConfig(frameHeight = 1080))
    val cfg = WaveConfig()
    for ((ts, rows) in byTs) {
        val dets = rows.filter { it[1].toFloat() >= 0.25f }.map {
            Detection(BoundingBox(it[2].toFloat(), it[3].toFloat(), it[4].toFloat(), it[5].toFloat()), it[1].toFloat(), ts)
        }
        val tracks = tracker.update(dets, ts)
        if (ts in from..to) {
            val s = tracks.filter { it.lastSeenUs == ts && it.box.height >= 25 }.joinToString(" | ") {
                val sp = it.speedBodyLengthsPerSec(cfg.speedWindowUs)
                val asp = it.meanAspect(cfg.speedWindowUs)
                "n%d h%.0f x%.0f v=%s asp=%s hits=%d".format(it.id, it.box.height, it.box.centerX, sp?.let { v -> "%.1f".format(v) } ?: "-", asp?.let { v -> "%.2f".format(v) } ?: "-", it.hits)
            }
            println("%d:%02d.%d  %s".format(ts / 60_000_000, (ts / 1_000_000) % 60, (ts / 100_000) % 10, s))
        }
    }
}
