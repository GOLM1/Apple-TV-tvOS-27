package io.github.besliky.airplaytv.service

/**
 * Which RTP time each recent block written to the AudioTrack starts with, by the track frame it
 * starts at, so the frame being heard can be turned back into the sender's RTP time.
 * Thread safe: the writer records, the main thread looks up.
 */
internal class PlayedRtp(private val capacity: Int = 128) {
    private val frames = LongArray(capacity)
    private val rtps = LongArray(capacity)
    private var count = 0
    private var next = 0

    @Synchronized
    fun record(trackFrame: Long, rtp: Long) {
        frames[next] = trackFrame
        rtps[next] = rtp
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** The RTP time (0..2^32-1) of [trackFrame], or -1 if it is not among the recent blocks or unknown. */
    @Synchronized
    fun rtpAt(trackFrame: Long): Long {
        var best = -1
        for (k in 0 until count) {
            val i = (next - 1 - k + capacity) % capacity
            if (frames[i] <= trackFrame) {
                best = i
                break
            }
        }
        if (best < 0 || rtps[best] < 0) return -1
        return (rtps[best] + (trackFrame - frames[best])) and 0xFFFFFFFFL
    }

    @Synchronized
    fun clear() {
        count = 0
        next = 0
    }
}
