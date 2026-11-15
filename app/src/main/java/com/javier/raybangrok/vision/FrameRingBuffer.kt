package com.javier.raybangrok.vision

/** Buffer circular de frames muestreados (thread-safe). Los más antiguos se descartan. */
class FrameRingBuffer(private val capacity: Int = 30) {
    private val items = ArrayDeque<Frame>(capacity)

    @Synchronized fun add(frame: Frame) {
        if (items.size == capacity) items.removeFirst()
        items.addLast(frame)
    }

    @Synchronized fun latest(): Frame? = items.lastOrNull()

    /** Frames con timestamp > [sinceMs], en orden cronológico. */
    @Synchronized fun since(sinceMs: Long): List<Frame> = items.filter { it.timestampMs > sinceMs }

    @Synchronized fun clear() = items.clear()

    @get:Synchronized val size: Int get() = items.size
}
