package com.openbrowser.mobile.sync

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** Activity 使用共享锁；同步导入使用排他锁。进程死亡后由 OS 自动释放。 */
class ProfileLeases private constructor(private val ids: List<String>) : Closeable {
    private var closed = false
    override fun close() = synchronized(held) {
        if (!closed) { closed = true; ids.asReversed().forEach { release(it) } }
    }
    companion object {
        private data class Held(val file: RandomAccessFile, val lock: FileLock, val shared: Boolean, var count: Int = 1)
        private val held = mutableMapOf<String, Held>()
        private fun release(id: String) { held[id]?.let { if (--it.count == 0) { held.remove(id); runCatching { it.lock.release() }; it.file.close() } } }
        fun acquire(context: Context, ids: Collection<String>, shared: Boolean = false): ProfileLeases? = synchronized(held) {
            val directory = File(context.applicationContext.filesDir, "sync_leases").apply { mkdirs() }
            val acquired = mutableListOf<String>()
            try {
                ids.distinct().sorted().forEach { id ->
                    require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                    val existing = held[id]
                    if (existing != null) {
                        check(shared && existing.shared) { "环境正在使用" }; existing.count++
                    } else {
                        val file = RandomAccessFile(File(directory, "$id.lock"), "rw")
                        val lock = try { file.channel.tryLock(0, Long.MAX_VALUE, shared) } catch (e: Exception) { file.close(); throw e }
                        if (lock == null) { file.close(); error("环境正在使用") }
                        held[id] = Held(file, lock, shared)
                    }
                    acquired.add(id)
                }
                ProfileLeases(acquired)
            } catch (_: Exception) { acquired.asReversed().forEach { release(it) }; null }
        }
    }
}
