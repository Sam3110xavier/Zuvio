# Upstream integration audit: A <- b-upstream

## Safety and branch state

- Target of the eventual sync: `A`.
- Upstream reference: `b-upstream`.
- Protected snapshot: `A-pre-upstream-sync`.
- All integration work is on `A-upstream-integration`; `A` has not been modified.
- The integration branch is based on the exact original `A` head and preserves its history.
- Original branch comparison: `b-upstream` was 74 commits ahead and `A` was 34 commits ahead of `b-upstream`; the branches had diverged from common base `9179e3c6f578c4ecd3a2e1fb4372612ca7c3ca32`.

## Change accounting

- 159 upstream-changed paths were identified.
- 127 paths changed only on the upstream side were imported using the upstream file blobs and original file modes.
- 32 paths changed on both sides were three-way reviewed against the common base. Independent upstream hunks were integrated alongside A's changes.
- A's 198 paths that were changed only on A relative to the common base were left intact.
- The integration branch currently contains 159 changed paths relative to A.
- 55 upstream-only string resources were added to the primary `values/strings.xml` resource file.
- No force-push or direct edit was made to `A`.

## Collision register and integration decisions

### 1. Application shell and downloaded-item navigation

**Files:** `AppShellComponents.kt`, `MainAppContent.kt`

Upstream adds an `onOpenDownload` callback to open a downloaded item directly from the home/library surfaces. A already has its own search-tab callback and its custom navigation wiring.

**Resolution:** Added `onOpenDownload` alongside A's existing `onSearchClick`; wired it to the existing `openDownloadedItem` handler. Neither callback replaces the other.

### 2. Detail hero mute-button positioning

**File:** `features/details/components/DetailHero.kt`

Upstream aligns the mute control to the native navigation-bar button anchor. A has Netflix-style hero sizing, left-aligned metadata/actions, and a black lower gradient.

**Resolution:** Integrated the native-bar vertical and trailing anchors while keeping A's hero-height selection, Netflix-style content layout, and gradient behavior.

### 3. Library projection/filter model

**File:** `features/library/LibraryDisplaySettings.kt`

Upstream adds genre, year, and watched-state filtering and an explicit media-type order. A has a `selectSection` option and a special WETRAKR media-type projection.

**Resolution:** Retained A's `selectSection` parameter and WETRAKR's fixed movie/series behavior; added upstream genre/year/watched parameters and filtering; adopted upstream's ordered type list for other sources.

### 4. Library filter presentation

**File:** `features/library/LibrarySavedContent.kt`

A uses its existing type chips, sort control, section controls, and full-width/spacing treatment. Upstream adds genre, year, and watched filters.

**Resolution:** Kept A's existing first and section-control rows. Added upstream's genre/year/watched controls in a separate filter row below them, including counts and callbacks. This avoids replacing A's layout with the upstream dropdown-only arrangement.

### 5. Stream source resolution: A's CloudStream flow plus upstream offline sources

**File:** `features/streams/StreamsRepository.kt`

A has CloudStream direct-route dispatch, CloudStream provider groups, Telegram availability, and its own auto-play safeguards. Upstream adds locally downloaded stream grouping, embedded-stream coexistence, and fallback to a local file when no remote source is available.

**Resolution:** Preserved A's CloudStream direct-route early return and CloudStream/Plugin/Server/Telegram group discovery. Added the downloaded group to ordinary stream requests after the explicit CloudStream route path; included it with embedded streams and prepended it to the normal ordered source groups; added downloaded-only fallback for the no-provider and no-compatible-provider cases. Kept A's stricter `!qualitySelectionMode` condition for binge-group direct auto-play rather than relaxing it.

**QA focus:** Verify movie and episode matching, subtitle forwarding, downloaded-only playback, normal addon results with a download present, embedded streams, CloudStream direct links, and manual/quality-selection behavior.

### 6. Shared string keys with different wording

**File:** `composeApp/src/commonMain/composeResources/values/strings.xml`

For these nine same-key wording differences, A's wording was retained because it describes behavior present in A or is more complete. Upstream-only keys were added separately.

| Resource key | Why A's wording was retained |
|---|---|
| `profile_background_member_note` | A describes built-in backgrounds and its in-app custom image/GIF URL flow; upstream wording refers to web-panel configuration. |
| `profile_custom_background_url_description` | A explicitly supports image/GIF links; upstream describes the more limited image URL flow. |
| `profile_custom_background_url_placeholder` | A's example is a GIF URL, consistent with A's support. |
| `profile_background_url_invalid` | A's validation text accurately includes image and GIF URLs. |
| `settings_advanced_debug_logs_description` | A describes export and crash-report support in addition to log viewing/copying/clearing. |
| `settings_advanced_debugging_clear_logs_description` | A includes locally saved crash reports; upstream describes only the in-memory debug buffer. |
| `settings_advanced_debugging_log_viewer_description` | A explains both in-memory logs and locally stored crash reports. |
| `settings_homescreen_pin_to_move_toast` | A's message covers both row pins and group locks; upstream mentions only pin-to-top. |
| `settings_playback_private_viewing_description` | A explicitly describes suppressing watch progress, Continue Watching, watched status, and tracker sync. |

**Follow-up approach:** Keep A's current wording unless the underlying behavior is deliberately changed. Any later copy update should describe the actual combined behavior, not reduce support in the UI copy.

### 7. iOS release version

**File:** `iosApp/Configuration/Version.xcconfig`

A was on build 156 / version 0.6.13; upstream is on build 158 / version 1.1.0-beta.1.

**Resolution:** Adopted upstream's build/version values and retained A's 0.6.13 checkpoint note as historical context.

## Three-way reviewed overlapping paths

The 32 paths reviewed against the common base were:

- `.github/workflows/mobile-release.yml`
- `composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt`
- `composeApp/src/commonMain/composeResources/values/strings.xml`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/AppNavigationSupport.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/AppShellComponents.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/StreamDestination.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/addons/AddonModels.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/addons/AddonRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/addons/AddonsScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailHero.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryDisplaySettings.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibrarySavedContent.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeSourceActions.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/ProfileRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsRootPage.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsSearch.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamAutoPlaySelector.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamCard.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamsRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamsScreen.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/navigation/Routes.kt`
- `composeApp/src/commonTest/kotlin/com/nuvio/app/features/library/LibraryDisplaySettingsTest.kt`
- `iosApp/Configuration/Version.xcconfig`

## Validation status

- The original A branch and its backup branch were not modified during integration.
- Structural review confirms the Library filter parameters are wired from the screen into the projection and the three new filters are rendered.
- Structural review confirms downloaded-source grouping is included with ordinary stream sources and the CloudStream direct-route implementation remains present.
- A build/test run is still required before merging. In particular, run the Kotlin Multiplatform host/common tests and Android debug build, then exercise the Library filters, downloaded playback, CloudStream playback, and hero mute-button positioning on device.
- The integration branch must only become the new A after all required CI and manual QA checks pass.
