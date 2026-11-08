package com.nuvio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime smoke test for the aligned Media3 dependency stack.
 *
 * This catches mixed-version packaging where an extension module references a
 * Media3 class that is missing from the packaged ExoPlayer runtime.
 */
@RunWith(AndroidJUnit4::class)
class Media3RuntimeCompatibilityTest {
    @Test
    fun requiredMedia3ClassesArePresentAtRuntime() {
        val requiredClasses = listOf(
            "androidx.media3.common.MediaItem",
            "androidx.media3.exoplayer.ExoPlayer",
            "androidx.media3.exoplayer.source.LoadEventInfo\$Builder",
            "androidx.media3.exoplayer.dash.DashMediaSource",
            "androidx.media3.exoplayer.hls.HlsMediaSource",
            "androidx.media3.exoplayer.smoothstreaming.SsMediaSource",
            "androidx.media3.exoplayer.rtsp.RtspMediaSource",
            "androidx.media3.datasource.okhttp.OkHttpDataSource",
            "androidx.media3.database.StandaloneDatabaseProvider",
            "androidx.media3.ui.PlayerView",
            "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
            "androidx.media3.decoder.ffmpeg.FfmpegLibrary",
            "androidx.media3.decoder.av1.Libgav1VideoRenderer",
            "androidx.media3.decoder.mpegh.MpeghAudioRenderer",
            "androidx.media3.decoder.mpegh.MpeghLibrary",
        )

        requiredClasses.forEach { className ->
            assertNotNull(
                "Missing Media3 runtime class: $className",
                Class.forName(className),
            )
        }
    }
}
