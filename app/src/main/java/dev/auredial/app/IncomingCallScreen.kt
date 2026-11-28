package dev.auredial.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun IncomingCallScreen(call: CallView, otherCall: CallView?) {
    val scheme = MaterialTheme.colorScheme
    CallFrame { _ ->
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            CallStatusBar(stringResource(R.string.incoming_call), connected = false, call = call)
            CallIdentity(call, ringing = true)
            if (otherCall != null) {
                val name = otherCall.name ?: otherCall.number ?: stringResource(R.string.unknown_caller)
                AureCard {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        AureGlyph(R.drawable.aure_pause, tint = scheme.primary)
                        Text(stringResource(R.string.other_call, name), color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AureAction(
                stringResource(R.string.reject), { CallRuntime.action(call.id, "reject") },
                Modifier.weight(1f).heightIn(min = 64.dp), R.drawable.aure_decline,
                color = scheme.errorContainer, ink = scheme.onErrorContainer,
            )
            AureAction(
                stringResource(R.string.answer), { CallRuntime.action(call.id, "answer") },
                Modifier.weight(1f).heightIn(min = 64.dp), R.drawable.aure_answer,
                color = Color(0xFFAEEDCE), ink = Color(0xFF15372A),
            )
        }
    }
}
