package com.surfcam.app.domain.detection

/**
 * Limite la cadence d'analyse : accepte une image seulement si au moins [minIntervalUs] se sont écoulées depuis la
 * dernière acceptée. La toute première image est toujours acceptée.
 */
class FrameThrottle(@Volatile var minIntervalUs: Long) {

    private var lastAcceptedUs: Long? = null

    fun accept(timestampUs: Long): Boolean {
        val last = lastAcceptedUs
        if (last != null && timestampUs - last < minIntervalUs) return false
        lastAcceptedUs = timestampUs
        return true
    }

    fun reset() {
        lastAcceptedUs = null
    }
}
