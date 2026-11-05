package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun TrackingDeviceCodeSheet(
    userCode: String?,
    verificationUrl: String,
    isConnected: Boolean,
    onOpenVerificationPage: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isConnected) "WeTrakr connected" else "Connect WeTrakr") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isConnected) {
                    Text("Your WeTrakr account is connected.")
                } else {
                    Text("Open the verification page and enter this code to authorize NuvioEx.")
                    if (!userCode.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = userCode,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        Text("Requesting an authorization code…")
                    }
                    Text(
                        text = verificationUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            if (isConnected) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                Button(onClick = { onOpenVerificationPage(verificationUrl) }) {
                    Text("Open verification page")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (!isConnected) onCancel()
                onDismiss()
            }) {
                Text(if (isConnected) "Close" else "Cancel")
            }
        },
    )
}
