package com.unitrading.app

import kotlinx.coroutines.runBlocking
import unitrading.composeapp.generated.resources.Res
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.*

class TradeSoundResourcesTest {
    @Test fun allBundledClipsDecodeAsShortMonoPcmAudio() = runBlocking {
        val durations = (listOf(TradeSounds.takeProfit, TradeSounds.entry) + TradeSounds.stopLoss).map { path ->
            val bytes = Res.readBytes(path)
            AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { audio ->
                assertEquals(AudioFormat.Encoding.PCM_SIGNED, audio.format.encoding)
                assertEquals(1, audio.format.channels)
                assertEquals(16, audio.format.sampleSizeInBits)
                assertEquals(24_000f, audio.format.sampleRate)
                assertTrue(audio.readAllBytes().any { it != 0.toByte() }, "$path contains only silence")
                audio.frameLength / audio.format.frameRate.toDouble()
            }
        }
        assertEquals(1.08, durations.first(), .001)
        assertEquals(.62, durations[1], .001)
        assertEquals(14, durations.size)
        assertTrue(durations.drop(2).all { it in .8..3.0 })
    }
}
