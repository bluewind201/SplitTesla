@file:Suppress("unused")
package android.media

import android.os.Handler

class AudioAttributes private constructor() {
    class Builder {
        fun setUsage(u: Int) = this
        fun setContentType(c: Int) = this
        fun build() = AudioAttributes()
    }
    companion object { const val USAGE_ASSISTANCE_NAVIGATION_GUIDANCE = 12; const val CONTENT_TYPE_SPEECH = 1 }
}
class AudioFocusRequest private constructor(val listener: AudioManager.OnAudioFocusChangeListener?) {
    class Builder(@Suppress("UNUSED_PARAMETER") g: Int) {
        private var l: AudioManager.OnAudioFocusChangeListener? = null
        fun setAudioAttributes(a: AudioAttributes) = this
        fun setOnAudioFocusChangeListener(l: AudioManager.OnAudioFocusChangeListener, h: Handler) = apply { this.l = l }
        fun build() = AudioFocusRequest(l)
    }
}
class AudioManager {
    fun interface OnAudioFocusChangeListener { fun onAudioFocusChange(change: Int) }
    var grant = true
    var held = 0
    var requests = 0
    var last: AudioFocusRequest? = null
    fun requestAudioFocus(r: AudioFocusRequest): Int {
        requests++; last = r
        if (!grant) return AUDIOFOCUS_REQUEST_FAILED
        held = 1
        return AUDIOFOCUS_REQUEST_GRANTED
    }
    fun abandonAudioFocusRequest(r: AudioFocusRequest): Int { held = 0; return AUDIOFOCUS_REQUEST_GRANTED }
    companion object {
        const val AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3
        const val AUDIOFOCUS_LOSS = -1
        const val AUDIOFOCUS_LOSS_TRANSIENT = -2
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
        const val AUDIOFOCUS_REQUEST_FAILED = 0
    }
}
