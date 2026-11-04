package com.nuvio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime smoke test for the Media3 module migration.
 *
 * Specifically guards against the previous crash where the newer DASH module
 * referenced LoadEventInfo.Builder while the packaged ExoPlayer core was older.
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
            // Custom codec extension AARs remain local; verify their Java entry points
            // still link against the updated Media3 core before attempting playback.
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
