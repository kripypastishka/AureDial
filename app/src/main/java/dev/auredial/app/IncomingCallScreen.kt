package dev.auredial.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun IncomingCallScreen(call: CallView, otherCall: CallView?) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val alert = call.verificationFailed
    val avatarBackground = when {
        alert && dark -> Color(0xFF37242B)
        alert -> Color(0xFFFFE6E9)
        dark -> Color(0xFF252A45)
        else -> Color(0xFFDFE4FD)
    }
    val avatarTint = if (alert) {
        if (dark) Color(0xFFFF8996) else Color(0xFFAC2638)
    } else scheme.primary
    val contactTint = if (dark) Color(0xFF82D9AB) else Color(0xFF176844)
    val title = call.name ?: stringResource(R.string.unknown_caller)
    val secondary = listOfNotNull(call.number, call.accountLabel).joinToString(" · ")

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (dark) Color(0xFF121318) else Color(0xFFF4F5FA),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(top = 22.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.incoming_call), color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Surface(Modifier.size(80.dp), shape = CircleShape, color = avatarBackground) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (alert) R.drawable.aure_shield_alert else R.drawable.aure_person),
                            contentDescription = null, modifier = Modifier.size(36.dp), tint = avatarTint)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (secondary.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(secondary, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                if (alert || call.fromContacts) {
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!alert) Icon(painterResource(R.drawable.aure_shield_check), null, Modifier.size(15.dp), tint = contactTint)
                        Text(stringResource(if (alert) R.string.number_verification_failed else R.string.from_contacts),
                            color = if (alert) avatarTint else contactTint, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (otherCall != null) {
                    Spacer(Modifier.height(22.dp))
                    val otherTitle = otherCall.name ?: otherCall.number ?: stringResource(R.string.unknown_caller)
                    Text(stringResource(R.string.other_call, otherTitle), color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                modifier = Modifier.widthIn(max = 350.dp).fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallControl(R.string.reject, R.drawable.aure_decline, Color(0xFFB52D40), Modifier.weight(1f)) {
                    CallRuntime.action(call.id, "reject")
                }
                CallControl(R.string.answer, R.drawable.aure_answer, Color(0xFF177449), Modifier.weight(1f)) {
                    CallRuntime.action(call.id, "answer")
                }
            }
        }
    }
}

@Composable
private fun CallControl(@StringRes label: Int, @DrawableRes icon: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val description = stringResource(label)
    Column(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(modifier = Modifier.size(64.dp), shape = CircleShape, color = color, contentColor = Color.White) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(description, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}
