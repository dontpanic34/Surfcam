import com.surfcam.app.domain.clip.ClipAction
import com.surfcam.app.domain.clip.ClipPlanner
import com.surfcam.app.domain.tracking.BoundingBox
import com.surfcam.app.domain.tracking.Detection
import com.surfcam.app.domain.tracking.LegacyWaveDetector
import com.surfcam.app.domain.tracking.SurferTracker
import com.surfcam.app.domain.tracking.TrackerConfig
import com.surfcam.app.domain.tracking.WaveConfig
import com.surfcam.app.domain.tracking.WaveDetector
import com.surfcam.app.domain.tracking.WaveEvent
import java.io.File

/** Rejoue la vraie logique de détection (suivi, détecteur de vague, planificateur de clips) sur des détections déjà calculées. */
fun main(args: Array<String>) {
    val kv = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
    val legacy = kv["mode"] == "legacy"
    val base = WaveConfig()
    val cfg = base.copy(
        startSpeed = kv["speed"]?.toFloat() ?: base.startSpeed,
        stopSpeed = kv["stop"]?.toFloat() ?: base.stopSpeed,
        minRideUs = ((kv["minride"]?.toFloat() ?: 3f) * 1_000_000f).toLong(),
        minTriggerConfidence = kv["conf"]?.toFloat() ?: base.minTriggerConfidence,
        minHorizontalShare = kv["share"]?.toFloat() ?: base.minHorizontalShare,
        minStandingAspect = kv["aspect"]?.toFloat() ?: base.minStandingAspect,
        endGraceUs = ((kv["endgrace"]?.toFloat() ?: 1.5f) * 1_000_000f).toLong(),
        lostGraceUs = ((kv["lostgrace"]?.toFloat() ?: 4f) * 1_000_000f).toLong(),
        startGapUs = ((kv["gap"]?.toFloat() ?: 0f) * 1_000_000f).toLong(),
        minRideLeavingUs = ((kv["leaveride"]?.toFloat() ?: 1.2f) * 1_000_000f).toLong(),
    )
    val tc = TrackerConfig(frameHeight = 720)
    val tracker = SurferTracker(tc.copy(
        maxJumpInBodyLengths = kv["jump"]?.toFloat() ?: tc.maxJumpInBodyLengths,
        extraJumpPerSecond = kv["extra"]?.toFloat() ?: tc.extraJumpPerSecond,
        maxMissingUs = ((kv["missing"]?.toFloat() ?: 4f) * 1_000_000f).toLong(),
    ))
    val modern = WaveDetector(cfg)
    val old = LegacyWaveDetector(cfg)
    val planner = ClipPlanner()
    val frames = LinkedHashMap<Double, MutableList<Detection>>()
    File(args[0]).forEachLine { line ->
        val p = line.split(',')
        val t = p[0].toDouble()
        val list = frames.getOrPut(t) { mutableListOf() }
        if (p[1] != "-1") list += Detection(BoundingBox(p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat()), p[1].toFloat(), (t * 1_000_000).toLong())
    }
    val out = StringBuilder()
    for ((t, dets) in frames) {
        val ts = (t * 1_000_000).toLong()
        val tracks = tracker.update(dets, ts)
        val event = if (legacy) old.onFrame(tracks, ts) else modern.onFrame(tracks, ts)
        val actions = (if (event != null) planner.onWave(event, ts) else emptyList()) + planner.onTick(ts)
        if (event is WaveEvent.Started) out.append("TRIGGER,%.2f,%d,%.2f,%.0f,%.2f,%.2f,%.0f\n".format(t, event.trackId, event.speed, event.horizontalShare * 100, event.aspect, event.confidence, event.boxHeight))
        if (event is WaveEvent.Ended) out.append("ENDED,%.2f,%.2f,%.2f\n".format(t, event.takeoffUs / 1e6, event.endUs / 1e6))
        if (event is WaveEvent.Cancelled) out.append("CANCELLED,%.2f,%.2f,%b,%.2f\n".format(t, event.takeoffUs / 1e6, event.endedByLoss, (event.stats?.durationUs ?: 0L) / 1e6))
        for (a in actions) when (a) {
            is ClipAction.Start -> out.append("START,%.2f\n".format(t))
            is ClipAction.Discard -> out.append("DISCARD,%.2f\n".format(t))
            is ClipAction.Stop -> out.append("STOP,%.2f,%.2f\n".format(t, a.atUs / 1e6))
            else -> {}
        }
    }
    print(out)
}
