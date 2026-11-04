package com.splittesla.core.navi

/** A rejected raw sample does not erase a recent authorized integration tick. */
object GuidanceDisplayPolicy {
    fun estimateStillLive(wasEstimating: Boolean, hasAnchor: Boolean, lastTickAt: Long,
                          monotonicNow: Long, now: Long, lastFixAt: Long,
                          speedKmh: Double?, speedAt: Long): Boolean =
        wasEstimating && hasAnchor && monotonicNow - lastTickAt in 0..1500 &&
            SpeedEvidence.deadReckoningKmh(now, lastFixAt, speedKmh, speedAt)?.let { it > 3 } == true
}
