package com.unitrading.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import unitrading.composeapp.generated.resources.Res
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem

internal actual fun platformTradeSoundPlayer(): TradeSoundPlayer = object : TradeSoundPlayer {
    override suspend fun play(resourcePath: String) = withContext(Dispatchers.IO) {
        val bytes = Res.readBytes(resourcePath)
        AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { audio ->
            AudioSystem.getClip().use { clip ->
                try {
                    clip.open(audio)
                    ensureActive()
                    clip.start()
                    // Clip.start() may return before isRunning becomes true.
                    delay(20)
                    while (clip.isRunning || clip.framePosition < clip.frameLength) {
                        delay(20)
                    }
                } finally { clip.stop() }
            }
        }
    }
}
