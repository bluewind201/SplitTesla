// JVM stand-ins for the Android APIs Speaker.kt and VoiceChoice.kt use. NOT real Android:
// passing against these says nothing about android.jar signatures or device behaviour.
@file:Suppress("unused")
package android.os

object SystemClock { var now = 1_000L; fun elapsedRealtime() = now }
class Bundle
class Looper { companion object { private val main = Looper(); fun getMainLooper() = main } }
class Handler(@Suppress("UNUSED_PARAMETER") l: Looper) {
    fun post(r: Runnable): Boolean { FakeMain.add(0, r); return true }
    fun postDelayed(r: Runnable, ms: Long): Boolean { FakeMain.add(ms, r); return true }
}
/** Virtual-time main looper. */
object FakeMain {
    private class T(val at: Long, val seq: Long, val r: Runnable)
    private val tasks = mutableListOf<T>()
    private var seq = 0L
    fun add(ms: Long, r: Runnable) { tasks += T(SystemClock.now + ms, seq++, r) }
    fun reset() { tasks.clear(); SystemClock.now = 1_000L }
    fun drain() {
        while (true) {
            val t = tasks.filter { it.at <= SystemClock.now }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: return
            tasks.remove(t); t.r.run()
        }
    }
    fun advance(ms: Long) {
        val end = SystemClock.now + ms
        while (true) {
            drain()
            val t = tasks.filter { it.at <= end }.minByOrNull { it.at } ?: break
            SystemClock.now = maxOf(SystemClock.now, t.at)
        }
        SystemClock.now = end
        drain()
    }
}
