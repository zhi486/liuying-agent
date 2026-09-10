package com.healthyagent.running.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.util.concurrent.atomic.AtomicBoolean

class AndroidAudioFocusController internal constructor(
    private val platform: AndroidAudioFocusPlatform,
) : AudioFocusController {
    constructor(context: Context) : this(
        AndroidAudioFocusPlatformAdapter(
            requireNotNull(context.getSystemService(AudioManager::class.java)),
        ),
    )

    override fun request(): AudioFocusLease {
        val request = platform.requestFocus(
            contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
            usage = AudioAttributes.USAGE_ASSISTANT,
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
        return OnceAudioFocusLease(request.granted) {
            platform.abandonFocus(request)
        }
    }
}

internal interface AndroidAudioFocusPlatform {
    fun requestFocus(contentType: Int, usage: Int, focusGain: Int): AndroidAudioFocusRequest
    fun abandonFocus(request: AndroidAudioFocusRequest)
}

internal interface AndroidAudioFocusRequest {
    val granted: Boolean
}

internal class OnceAudioFocusLease(
    override val granted: Boolean,
    private val abandon: () -> Unit,
) : AudioFocusLease {
    private val released = AtomicBoolean(false)

    override fun release() {
        if (released.compareAndSet(false, true)) {
            abandon()
        }
    }
}

private class AndroidAudioFocusPlatformAdapter(
    private val audioManager: AudioManager,
) : AndroidAudioFocusPlatform {
    override fun requestFocus(contentType: Int, usage: Int, focusGain: Int): AndroidAudioFocusRequest {
        val request = AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(contentType)
                    .setUsage(usage)
                    .build(),
            )
            .build()
        return AndroidAudioFocusRequestAdapter(
            request = request,
            granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
        )
    }

    override fun abandonFocus(request: AndroidAudioFocusRequest) {
        audioManager.abandonAudioFocusRequest((request as AndroidAudioFocusRequestAdapter).request)
    }
}

private data class AndroidAudioFocusRequestAdapter(
    val request: AudioFocusRequest,
    override val granted: Boolean,
) : AndroidAudioFocusRequest
