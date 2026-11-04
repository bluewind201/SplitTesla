package com.splittesla.core.voice

/** Application policy, unrelated to a particular TTS engine. Times are monotonic milliseconds. */
enum class SpeechOutcome { ACCEPTED, STARTED, COMPLETED, DROPPED, INTERRUPTED }

class SpeechQueue(private val capacity: Int = 32) {
    data class Request(val owner: Long, val text: String, val key: String, val priority: Int,
                       val expiresAt: Long, val group: String? = null, val voiceName: String? = null, val rate: Float? = null,
                       val deduplicate: Boolean = priority != 70,
                       val validAtStart: () -> Boolean = { true },
                       val onOutcome: (SpeechOutcome) -> Unit = {})
    private val waiting = mutableListOf<Request>()
    private val recent = mutableMapOf<String, Long>()
    var active: Request? = null
        private set

    fun offer(request: Request, now: Long): Boolean {
        prune(now)
        fun rejected(): Boolean { request.onOutcome(SpeechOutcome.DROPPED); return false }
        if (request.text.isBlank() || request.expiresAt <= now || (request.deduplicate && (active?.key == request.key ||
            waiting.any { it.key == request.key } || recent.containsKey(request.key)))) return rejected()
        // A newer camera stage replaces an obsolete waiting stage, never a playing sentence.
        if (request.group != null) dropWaiting { it.owner == request.owner && it.group == request.group }
        if (waiting.size >= capacity) {
            val lowest = waiting.minByOrNull { it.priority } ?: return rejected()
            if (lowest.priority >= request.priority) return rejected()
            waiting.remove(lowest)
            lowest.onOutcome(SpeechOutcome.DROPPED)
        }
        waiting += request
        return true
    }

    /** Final camera warnings may cut a turn; other essential guidance only cuts conversation. */
    fun shouldPreempt(request: Request): Boolean = active?.let { (request.priority >= 100 && it.priority < 100) || (request.priority >= 60 && it.priority < 60) } == true

    fun next(now: Long): Request? {
        prune(now)
        if (active != null) return null
        val next = waiting.maxByOrNull { it.priority } ?: return null
        waiting.remove(next)
        if (!next.validAtStart()) { next.onOutcome(SpeechOutcome.DROPPED); return this.next(now) }
        active = next
        if (next.deduplicate) recent[next.key] = now + 8000
        return next
    }

    fun finish(outcome: SpeechOutcome? = null): Request? = active.also { active = null; if (outcome != null) it?.onOutcome?.invoke(outcome) }
    /** Returns true only when this owner's playing sentence must be stopped. */
    fun cancel(owner: Long): Boolean {
        dropWaiting { it.owner == owner }
        if (active?.owner != owner) return false
        finish(SpeechOutcome.DROPPED)
        return true
    }
    fun clear() { dropWaiting { true }; finish(SpeechOutcome.DROPPED) }
    private fun dropWaiting(test: (Request) -> Boolean) {
        val removed = waiting.filter(test)
        waiting.removeAll(removed.toSet())
        removed.forEach { it.onOutcome(SpeechOutcome.DROPPED) }
    }
    private fun prune(now: Long) {
        dropWaiting { it.expiresAt <= now }
        recent.entries.removeAll { it.value <= now }
    }
}
