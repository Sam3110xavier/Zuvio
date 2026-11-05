package com.nuvio.app.core.logging

import androidx.compose.runtime.Composable

/** Platform-backed export action for the currently displayed in-app logs. */
@Composable
expect fun rememberLogExportAction(): (String) -> Unit
