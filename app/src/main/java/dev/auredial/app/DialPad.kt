package dev.auredial.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.auredial.domain.t9Matches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DialPage(initial: String, isDefault: Boolean, back: () -> Unit, onDial: (String) -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var number by rememberSaveable { mutableStateOf(initial.toDialInput()) }
    var contacts by remember { mutableStateOf(emptyList<PhoneRow>()) }
    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) {
            runCatching { readPeople(context, false) }.getOrDefault(emptyList())
        }
    }
    val matches = remember(contacts, number) {
        if (number.length < 2) emptyList() else contacts.asSequence()
            .filter { t9Matches(number, it.name, it.number) }.take(3).toList()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        PageTop(stringResource(R.string.dial), back, closeLabel = stringResource(R.string.close_keypad))
        if (!isDefault) {
            Text(stringResource(R.string.dial_note), modifier = Modifier.fillMaxWidth(), color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = number, onValueChange = { number = it.toDialInput() }, singleLine = true, shape = AureShapes.large,
            label = { Text(stringResource(R.string.phone_number)) },
            textStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, letterSpacing = 1.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = scheme.surface, unfocusedContainerColor = scheme.surface),
            trailingIcon = {
                AureIconButton(
                    R.drawable.aure_erase, stringResource(R.string.delete_digit), { number = number.dropLast(1) },
                    modifier = Modifier.padding(end = 6.dp), enabled = number.isNotEmpty(), onLongClick = { number = "" },
                )
            },
        )
        DigitGrid(
            letters = stringResource(R.string.keypad_letters).split('|'),
            onZeroHold = { if (number.isEmpty()) number = "+" },
        ) { number = (number + it).toDialInput() }
        AureAction(
            stringResource(if (isDefault) R.string.make_call else R.string.open_dialer),
            onClick = { onDial(number) }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.aure_answer,
            enabled = number.isNotBlank() && number != "+",
            color = scheme.secondary, ink = scheme.onSecondary,
        )
        if (matches.isNotEmpty()) {
            AureCard {
                matches.forEach { row ->
                    AureOption(
                        row.name ?: row.number, R.drawable.aure_person, subtitle = row.number.takeIf { row.name != null },
                        onClick = { number = row.number.toDialInput() },
                    )
                }
            }
        }
    }
}

@Composable
internal fun DigitGrid(letters: List<String>? = null, onZeroHold: (() -> Unit)? = null, onDigit: (Char) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val compact = letters == null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)) {
        listOf("123", "456", "789", "*0#").forEach { digits ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                digits.forEach { digit ->
                    val hint = letters?.getOrNull(digit - '2') ?: "+".takeIf { digit == '0' && onZeroHold != null }
                    Column(
                        Modifier.weight(1f).clip(AureShapes.medium).background(scheme.surfaceContainer)
                            .pressable(onLongClick = onZeroHold.takeIf { digit == '0' }, onClick = { onDigit(digit) })
                            .heightIn(min = if (compact) 48.dp else 63.dp).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text(digit.toString(), style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center, color = scheme.onSurface)
                        if (hint != null) {
                            Text(hint, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 0.9.sp, color = scheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun String.toDialInput(): String = buildString {
    for (character in this@toDialInput) {
        if (length == 40) break
        val digit = Character.digit(character, 10)
        when {
            digit >= 0 -> append(digit)
            character == '+' && isEmpty() -> append(character)
            character in "*#" -> append(character)
            character in ",;" && isNotEmpty() -> append(character)
        }
    }
}
