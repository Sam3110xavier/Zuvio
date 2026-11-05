package com.nuvio.app.features.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRatingProvider
import com.nuvio.app.features.tracking.TrackingRatingScope
import com.nuvio.app.features.tracking.TrackingRatingTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.tracking_rating_completion_checking
import nuvio.composeapp.generated.resources.tracking_rating_completion_failed
import nuvio.composeapp.generated.resources.tracking_rating_completion_not_now
import nuvio.composeapp.generated.resources.tracking_rating_completion_outstanding
import nuvio.composeapp.generated.resources.tracking_rating_completion_question
import nuvio.composeapp.generated.resources.tracking_rating_completion_submit
import nuvio.composeapp.generated.resources.tracking_rating_completion_title
import nuvio.composeapp.generated.resources.tracking_rating_completion_very_bad
import org.jetbrains.compose.resources.stringResource

private data class CompletionRatingOption(
    val provider: TrackingRatingProvider,
    val target: TrackingRatingTarget,
) {
    val providerId: TrackingProviderId get() = provider.providerId
}

@Composable
internal fun CompletionRatingPrompt(
    profileId: Int,
    seriesTitle: String,
    media: TrackingMediaReference,
    posterUrl: String?,
    onOptionsReady: () -> Unit,
    onDismiss: () -> Unit,
) {
    var options by remember(profileId, media) { mutableStateOf<List<CompletionRatingOption>>(emptyList()) }
    var isLoading by remember(profileId, media) { mutableStateOf(true) }
    var selectedProviderId by remember(profileId, media) { mutableStateOf<TrackingProviderId?>(null) }
    var selectedRating by remember(profileId, media) { mutableIntStateOf(0) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submissionError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val failedMessage = stringResource(Res.string.tracking_rating_completion_failed)

    LaunchedEffect(profileId, media, seriesTitle) {
        isLoading = true
        try {
            ensureTrackingProvidersRegistered()
            TrackingProviderRegistry.ensureLoaded()
            val target = TrackingRatingTarget(
                scope = TrackingRatingScope.SHOW,
                kind = media.kind,
                ids = media.ids,
                title = media.title?.takeIf(String::isNotBlank) ?: seriesTitle,
                year = media.year,
                catalog = media.catalog?.copy(videoId = null),
            )
            val available = buildList {
                for (provider in TrackingProviderRegistry.connectedRatingProviders()) {
                    try {
                        val normalized = provider.normalize(target)
                        if (normalized.scope in provider.supportedScopes &&
                            normalized.isValid && provider.canRate(normalized)
                        ) {
                            add(CompletionRatingOption(provider, normalized))
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // One provider failing to normalize must not hide the others.
                    }
                }
            }.sortedWith(
                compareBy<CompletionRatingOption> { providerOrder(it.providerId) }
                    .thenBy { it.providerId.ordinal },
            )
            options = available
            selectedProviderId = available.firstOrNull { it.providerId == TrackingProviderId.SIMKL }?.providerId
                ?: available.firstOrNull()?.providerId
            if (available.isEmpty()) {
                onDismiss()
            } else {
                onOptionsReady()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            options = emptyList()
            selectedProviderId = null
        } finally {
            isLoading = false
        }
    }

    val selectedOption = options.firstOrNull { it.providerId == selectedProviderId }
    if (isLoading) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(seriesTitle, maxLines = 2) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.tracking_rating_completion_checking))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.tracking_rating_completion_not_now))
                }
            },
        )
    } else if (selectedOption != null) {
        AlertDialog(
            onDismissRequest = { if (!isSubmitting) onDismiss() },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = selectedOption.providerId.displayName.uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            Res.string.tracking_rating_completion_title,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(
                            Res.string.tracking_rating_completion_question,
                            selectedOption.providerId.displayName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!posterUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = posterUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 64.dp, height = 92.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                        }
                        Text(
                            text = media.title?.takeIf(String::isNotBlank) ?: seriesTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 3,
                        )
                    }
                    if (options.size > 1) {
                        Text("Rate on", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            options.forEach { option ->
                                FilterChip(
                                    selected = option.providerId == selectedProviderId,
                                    onClick = {
                                        if (!isSubmitting) {
                                            selectedProviderId = option.providerId
                                            submissionError = null
                                        }
                                    },
                                    label = { Text(option.providerId.displayName) },
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        (1..10).forEach { value ->
                            FilterChip(
                                selected = selectedRating == value,
                                onClick = {
                                    if (!isSubmitting) {
                                        selectedRating = value
                                        submissionError = null
                                    }
                                },
                                label = { Text(value.toString()) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(Res.string.tracking_rating_completion_very_bad),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            stringResource(Res.string.tracking_rating_completion_outstanding),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    submissionError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isSubmitting && selectedRating in 1..10,
                    onClick = {
                        val option = selectedOption ?: return@Button
                        val rating = selectedRating
                        coroutineScope.launch {
                            isSubmitting = true
                            submissionError = null
                            try {
                                option.provider.setRating(profileId, option.target, rating)
                                onDismiss()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                submissionError = error.message?.takeIf(String::isNotBlank) ?: failedMessage
                            } finally {
                                isSubmitting = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(
                        Res.string.tracking_rating_completion_submit,
                        selectedOption.providerId.displayName,
                    ))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSubmitting,
                    onClick = onDismiss,
                ) {
                    Text(stringResource(Res.string.tracking_rating_completion_not_now))
                }
            },
        )
    }
}

private fun providerOrder(id: TrackingProviderId): Int = when (id) {
    TrackingProviderId.SIMKL -> 0
    TrackingProviderId.WETRAKR -> 1
    TrackingProviderId.TRAKT -> 2
    else -> 10 + id.ordinal
}
