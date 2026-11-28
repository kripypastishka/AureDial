package dev.auredial.app

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Day = lightColorScheme(
    primary = Color(0xFF933F29), onPrimary = Color.White,
    primaryContainer = Color(0xFFF7DFD4), onPrimaryContainer = Color(0xFF682B1A),
    secondary = Color(0xFF3F6254), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEAE0), onSecondaryContainer = Color(0xFF244638),
    background = Color(0xFFF7F3EC), onBackground = Color(0xFF25211F),
    surface = Color(0xFFFFFBF5), onSurface = Color(0xFF25211F),
    surfaceVariant = Color(0xFFEFE7DD), onSurfaceVariant = Color(0xFF655B54),
    surfaceContainer = Color(0xFFF3EDE4), surfaceContainerLow = Color(0xFFFAF6EF),
    surfaceContainerHigh = Color(0xFFEFE7DD), surfaceContainerHighest = Color(0xFFEAE0D5),
    outline = Color(0xFF857970), outlineVariant = Color(0xFFDDD0C3),
    error = Color(0xFF9B3338), onError = Color.White,
    errorContainer = Color(0xFFF9DDE0), onErrorContainer = Color(0xFF78232D),
)

private val Night = darkColorScheme(
    primary = Color(0xFFFFBC9F), onPrimary = Color(0xFF4B2116),
    primaryContainer = Color(0xFF503126), onPrimaryContainer = Color(0xFFFFD9CA),
    secondary = Color(0xFFADDEC7), onSecondary = Color(0xFF14382B),
    secondaryContainer = Color(0xFF283B33), onSecondaryContainer = Color(0xFFC9F3E1),
    background = Color(0xFF191816), onBackground = Color(0xFFF8EEE4),
    surface = Color(0xFF211F1C), onSurface = Color(0xFFF8EEE4),
    surfaceVariant = Color(0xFF302A25), onSurfaceVariant = Color(0xFFCFC0B4),
    surfaceContainer = Color(0xFF282420), surfaceContainerLow = Color(0xFF25211E),
    surfaceContainerHigh = Color(0xFF332D27), surfaceContainerHighest = Color(0xFF3B332D),
    outline = Color(0xFF9C8C80), outlineVariant = Color(0xFF493D33),
    error = Color(0xFFFFB4B7), onError = Color(0xFF610E1C),
    errorContainer = Color(0xFF4D272A), onErrorContainer = Color(0xFFFFD7D9),
)

private val InCall = Night.copy(
    primary = Color(0xFFFFC095), onPrimary = Color(0xFF462512),
    primaryContainer = Color(0xFF483228), onPrimaryContainer = Color(0xFFFFDFC8),
    background = Color(0xFF161A19), onBackground = Color(0xFFF9F2E9),
    surface = Color(0xFF1E2422), onSurface = Color(0xFFF9F2E9),
    surfaceVariant = Color(0xFF303A36), onSurfaceVariant = Color(0xFFC2CDC7),
    surfaceContainer = Color(0xFF272F2C), surfaceContainerLow = Color(0xFF1E2522),
    surfaceContainerHigh = Color(0xFF2E3934), surfaceContainerHighest = Color(0xFF36423D),
    outline = Color(0xFF89A299), outlineVariant = Color(0xFF3D5048),
)

private val DefaultText = Typography()
private val AureTypography = DefaultText.copy(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 47.sp, lineHeight = 50.sp, letterSpacing = (-1.8).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-1.1).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 27.sp, lineHeight = 32.sp, letterSpacing = (-0.45).sp),
    titleLarge = DefaultText.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = DefaultText.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelMedium = DefaultText.labelMedium.copy(letterSpacing = 0.1.sp),
)

internal val AureShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp),
)

@Composable
internal fun AureTheme(onCall: Boolean = false, content: @Composable () -> Unit) {
    val palette = when {
        onCall -> InCall
        isSystemInDarkTheme() -> Night
        else -> Day
    }
    MaterialTheme(colorScheme = palette, typography = AureTypography, shapes = AureShapes, content = content)
}

@Composable
internal fun AureGlyph(@DrawableRes icon: Int, size: Dp = 22.dp, tint: Color = LocalContentColor.current, description: String? = null) {
    Icon(painterResource(icon), description, Modifier.size(size), tint)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.959f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "Control press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.combinedClickable(
        interactionSource = source, indication = LocalIndication.current, enabled = enabled,
        role = role, onLongClick = onLongClick, onClick = onClick,
    )
}

@Composable
internal fun AureIconButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.size(48.dp).clip(AureShapes.medium)
            .background(scheme.surfaceContainer)
            .pressable(enabled, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AureGlyph(icon, tint = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.55f), description = label)
    }
}

@Composable
internal fun AureCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier.fillMaxWidth(), color = color, shape = AureShapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun AureAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    ink: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.clip(AureShapes.medium)
            .background(if (enabled) color else scheme.surfaceContainerHigh)
            .pressable(enabled, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
    ) {
        val tint = if (enabled) ink else scheme.onSurfaceVariant
        if (icon != null) AureGlyph(icon, size = 20.dp, tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun AureBadge(
    text: String,
    @DrawableRes icon: Int? = null,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    ink: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier,
) {
    val animatedColor by animateColorAsState(color, tween(190), label = "Badge color")
    Row(
        modifier.clip(CircleShape).background(animatedColor).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) AureGlyph(icon, size = 14.dp, tint = ink)
        Text(text, color = ink, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun AureAvatar(
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    @DrawableRes glyph: Int = R.drawable.aure_person,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    ink: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val initials = remember(name) {
        name.orEmpty().trim().split(' ').filter(String::isNotBlank)
            .take(2).joinToString("") { it.take(1).uppercase() }
    }
    Box(modifier.size(size).clip(AureShapes.medium).background(color), contentAlignment = Alignment.Center) {
        if (initials.isBlank()) AureGlyph(glyph, size = size * 0.49f, tint = ink)
        else Text(initials, color = ink, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun AureHeading(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun AureSegments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            val fill by animateColorAsState(
                if (active) scheme.primaryContainer else scheme.surfaceContainer,
                tween(195), label = "Segment selection",
            )
            Box(
                Modifier.clip(AureShapes.small).background(fill)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                    .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun AureOption(
    title: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(AureShapes.medium).pressable(onClick = onClick)
            .heightIn(min = 62.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(AureShapes.small).background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            AureGlyph(icon, size = 19.dp, tint = tint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        AureGlyph(if (selected) R.drawable.aure_check else R.drawable.aure_chevron, size = 18.dp,
            tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
    }
}

@Composable
internal fun AureEmpty(
    @DrawableRes icon: Int,
    title: String,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    AureCard(color = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 25.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AureAvatar(null, glyph = icon, size = 51.dp)
            Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            if (message != null) Text(message, textAlign = TextAlign.Center,
                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AureSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
        shape = AureShapes.extraLarge,
    ) {
        Column(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 540.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
        )
    }
}

internal data class SheetChoice(val key: String?, val title: String, @DrawableRes val icon: Int)

@Composable
internal fun AureChoiceSheet(title: String, selection: String?, choices: List<SheetChoice>, onDismiss: () -> Unit, onChoose: (String?) -> Unit) {
    AureSheet(onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        choices.forEach { choice ->
            AureOption(choice.title, choice.icon, onClick = { onChoose(choice.key); onDismiss() }, selected = choice.key == selection)
        }
    }
}
