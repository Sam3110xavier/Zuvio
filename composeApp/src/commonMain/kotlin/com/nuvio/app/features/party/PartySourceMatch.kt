package com.nuvio.app.features.party

import com.nuvio.app.features.streams.StreamItem

/**
 * High-confidence source identity for the stream-picker label only.
 *
 * Display labels and filenames can be reused by different HTTP encodes, so they are not
 * enough to claim that a candidate is the same source. Exact playback is achieved by
 * opening the host's shared PartyStream URL + headers.
 */
internal fun PartySource.matches(stream: StreamItem): Boolean {
    val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val streamHash = stream.p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    if (hash != null || streamHash != null) {
        if (hash == null || streamHash == null || hash != streamHash) return false
        val sourceIndex = fileIdx ?: return false
        val streamIndex = stream.p2pFileIdx ?: return false
        return sourceIndex == streamIndex
    }

    val sourceVideoHash = videoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val streamVideoHash = stream.behaviorHints.videoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return sourceVideoHash != null && streamVideoHash != null && sourceVideoHash == streamVideoHash
}
