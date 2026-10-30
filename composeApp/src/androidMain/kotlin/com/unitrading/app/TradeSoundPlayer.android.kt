package com.unitrading.app

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import unitrading.composeapp.generated.resources.Res
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual fun platformTradeSoundPlayer(): TradeSoundPlayer = object : TradeSoundPlayer {
    override suspend fun play(resourcePath: String) {
        val bytes = Res.readBytes(resourcePath)
        withContext(Dispatchers.Main) {
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                player.isLooping = false
                player.setDataSource(object : MediaDataSource() {
                    override fun getSize() = bytes.size.toLong()
                    override fun close() {}
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if (size == 0) return 0
                        if (position < 0 || position >= bytes.size) return -1
                        val count = minOf(size, bytes.size - position.toInt())
                        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                        return count
                    }
                })
                suspendCancellableCoroutine<Unit> { continuation ->
                    player.setOnPreparedListener { if (continuation.isActive) it.start() }
                    player.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                    player.setOnErrorListener { _, what, extra ->
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Audio playback failed: $what/$extra"))
                        true
                    }
                    player.prepareAsync()
                }
            } finally { player.release() }
        }
    }
}
