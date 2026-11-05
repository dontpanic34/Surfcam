package com.surfcam.core

import com.surfcam.core.clip.ClipAction
import com.surfcam.core.clip.ClipPlanner
import com.surfcam.core.director.Command
import com.surfcam.core.director.Director
import com.surfcam.core.director.DirectorConfig
import com.surfcam.core.director.Subject
import com.surfcam.core.model.Frame
import com.surfcam.core.model.WorldProjection
import com.surfcam.core.ride.RideConfig
import com.surfcam.core.ride.RideDetector
import com.surfcam.core.ride.RideEvent
import com.surfcam.core.tracking.Track
import com.surfcam.core.tracking.Tracker
import com.surfcam.core.tracking.TrackerConfig

/** Ce que le cerveau rend pour une image. */
data class BrainOutput(
    val tracks: List<Track>,
    val rideEvent: RideEvent?,
    val clipActions: List<ClipAction>,
    val command: Command,
    val ridingTrackId: Int?,
)

/**
 * Le cerveau de SurfCam, sans rien d'Android : l'appli lui donne chaque image analysée (détections + cap à l'heure de la prise de
 * vue) et le cap actuel ; il rend les pistes, les vagues, les ordres d'enregistrement et les ordres de gimbal/zoom.
 *
 * Le même objet tourne sur le téléphone, dans le banc (rejeu de sessions réelles) et dans le simulateur : ce qui est validé sur
 * ordinateur est exactement ce qui tournera à l'eau.
 */
class SurfBrain(
    val subject: Subject,
    initialPeakDeg: Double = 0.0,
    trackerConfig: TrackerConfig = TrackerConfig(),
    rideConfig: RideConfig = RideConfig(),
    directorConfig: DirectorConfig = DirectorConfig(),
    val clips: ClipPlanner = ClipPlanner(),
) {
    val tracker = Tracker(trackerConfig)
    val rides = RideDetector(rideConfig)
    val director = Director(subject, initialPeakDeg, directorConfig)

    /**
     * @param frame image analysée, avec le cap à l'heure de sa prise de vue
     * @param currentHeadingDeg cap réel au moment de la décision (pour calculer l'ordre de gimbal)
     */
    fun onFrame(frame: Frame, currentHeadingDeg: Double = frame.headingDeg): BrainOutput {
        val now = frame.timeUs
        val observations = WorldProjection.observeAll(frame)
        val tracks = tracker.update(observations, now)
        // En mode moi, seule la silhouette au manchon peut déclencher un clip.
        val candidates = if (subject == Subject.ME) tracks.filter { it.isUser } else tracks
        val event = rides.onFrame(candidates, now)
        val actions = ArrayList<ClipAction>()
        if (event != null) {
            actions += clips.onRide(event, now)
            if (event is RideEvent.Ended && event.kept) {
                val takeoffAz = tracks.firstOrNull { it.id == event.trackId }?.points?.firstOrNull { it.timeUs >= event.takeoffUs }?.azimuthDeg
                if (takeoffAz != null) director.onRideKept(takeoffAz, now)
            }
        }
        actions += clips.onTick(now)
        val command = director.onFrame(frame, tracks, currentHeadingDeg, rides.ridingTrackId, clips.isRecording)
        return BrainOutput(tracks, event, actions, command, rides.ridingTrackId)
    }

    fun finish(nowUs: Long): List<ClipAction> = clips.finish(nowUs)
}
