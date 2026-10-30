package com.unitrading.app

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.create
import unitrading.composeapp.generated.resources.Res

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun platformTradeSoundPlayer(): TradeSoundPlayer = object : TradeSoundPlayer {
    override suspend fun play(resourcePath: String) {
        val bytes = Res.readBytes(resourcePath)
        require(bytes.isNotEmpty())
        withContext(Dispatchers.Main) {
            val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
            val player = AVAudioPlayer(data = data, error = null)
            try {
                player.numberOfLoops = 0
                check(player.prepareToPlay() && player.play()) { "Audio playback unavailable" }
                while (player.playing) delay(20)
            } finally { player.stop() }
        }
    }
}
