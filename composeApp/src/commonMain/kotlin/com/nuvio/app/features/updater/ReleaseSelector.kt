package com.nuvio.app.features.updater

internal object ReleaseSelector {
    private val prereleaseNamePattern = Regex(
        "(?:^|[\\s._-])(alpha|beta|rc|preview)(?:[\\s._-]|$)",
        RegexOption.IGNORE_CASE
    )

    fun eligibleReleases(
        releases: List<GitHubReleaseDto>,
        channel: UpdateChannel
    ): List<GitHubReleaseDto> {
        val candidates = releases.mapNotNull { release ->
            if (release.draft) return@mapNotNull null
            val version = releaseVersion(release) ?: return@mapNotNull null
            ReleaseCandidate(
                release = release,
                version = version,
                prerelease = isPrerelease(release, version)
            ).takeIf { channel == UpdateChannel.BETA || !it.prerelease }
        }

        // Keep GitHub's chronology between distinct release versions. Within one
        // version family, however, prefer its final promotion and then the newest
        // prerelease (beta.10 must precede beta.9).
        return candidates
            .groupBy { Triple(it.version.major, it.version.minor, it.version.patch) }
            .values
            .flatMap { sameVersion ->
                sameVersion.sortedWith { left, right ->
                    right.version.compareTo(left.version)
                }
            }
            .map(ReleaseCandidate::release)
    }

    private fun releaseVersion(release: GitHubReleaseDto): SemanticVersion? =
        VersionUtils.parse(release.tagName) ?: VersionUtils.parse(release.name)

    private fun isPrerelease(
        release: GitHubReleaseDto,
        version: SemanticVersion
    ): Boolean = release.prerelease ||
        version.prerelease.isNotEmpty() ||
        prereleaseNamePattern.containsMatchIn(release.name.orEmpty())

    private data class ReleaseCandidate(
        val release: GitHubReleaseDto,
        val version: SemanticVersion,
        val prerelease: Boolean
    )
}
