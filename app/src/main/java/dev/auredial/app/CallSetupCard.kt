package dev.auredial.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal enum class CallSetup(@field:StringRes val description: Int, @field:StringRes val action: Int?) {
    DEFAULT_APP(R.string.default_note, R.string.set_default),
    NOTIFICATIONS(R.string.call_alerts_needed, R.string.notifications),
    CHANNEL(R.string.call_channel_needed, R.string.incoming_call_settings),
    FULL_SCREEN(R.string.call_full_screen_needed, R.string.fullscreen),
    READY(R.string.call_setup_ready, null),
}

@Composable
internal fun CallSetupCard(status: CallSetup, onAction: (CallSetup) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.receive_calls_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(status.description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            status.action?.let {
                Button(onClick = { onAction(status) }) { Text(stringResource(it)) }
            }
        }
    }
}
