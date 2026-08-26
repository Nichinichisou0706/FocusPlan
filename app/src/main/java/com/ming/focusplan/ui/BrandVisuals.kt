package com.ming.focusplan.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ming.focusplan.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

enum class MascotMood {
    IDLE,
    WELCOME,
    WORKING,
    REVIEW,
    BLOCKED
}

@DrawableRes
private fun MascotMood.drawableRes(): Int = when (this) {
    MascotMood.IDLE -> R.drawable.mascot_idle
    MascotMood.WELCOME -> R.drawable.mascot_welcome
    MascotMood.WORKING -> R.drawable.mascot_working
    MascotMood.REVIEW -> R.drawable.mascot_review
    MascotMood.BLOCKED -> R.drawable.mascot_blocked
}

@Composable
fun FocusPlanBackdrop(
    mood: MascotMood,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val tint = when (mood) {
        MascotMood.WORKING -> MaterialTheme.colorScheme.primaryContainer
        MascotMood.REVIEW -> MaterialTheme.colorScheme.secondaryContainer
        MascotMood.BLOCKED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxWidth().height(148.dp)
                .background(tint.copy(alpha = 0.38f))
        )
        Mascot(
            mood = mood,
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp),
            size = 148.dp,
            subdued = true
        )
        content()
    }
}

@Composable
fun Mascot(
    mood: MascotMood,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
    subdued: Boolean = false
) {
    val targetAlpha = if (subdued) 0.16f else 1f
    val alpha by animateFloatAsState(targetAlpha, tween(220), label = "mascotAlpha")
    Image(
        painter = painterResource(mood.drawableRes()),
        contentDescription = contentDescription,
        modifier = modifier.size(size).alpha(alpha),
        contentScale = ContentScale.Fit
    )
}

private enum class PetState {
    SLEEPING,
    NORMAL,
    PRESSED,
    DRAGGING
}

@Composable
fun FloatingMascotPet(
    modifier: Modifier = Modifier,
    startInset: Dp = 12.dp,
    bottomInset: Dp = 90.dp
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val hitSize = 72.dp
        val minX = with(density) { startInset.toPx() }
        val maxX = with(density) { (maxWidth - hitSize - 12.dp).coerceAtLeast(startInset).toPx() }
        val minY = with(density) { 12.dp.toPx() }
        val maxY = with(density) { (maxHeight - hitSize - bottomInset).coerceAtLeast(12.dp).toPx() }
        var x by rememberSaveable { mutableFloatStateOf(Float.NaN) }
        var y by rememberSaveable { mutableFloatStateOf(Float.NaN) }
        var state by remember { mutableStateOf(PetState.NORMAL) }
        var activityToken by remember { mutableIntStateOf(0) }

        LaunchedEffect(maxX, maxY) {
            x = if (x.isNaN()) maxX else x.coerceIn(minX, maxX)
            y = if (y.isNaN()) maxY else y.coerceIn(minY, maxY)
        }
        LaunchedEffect(activityToken) {
            delay(15_000)
            if (state == PetState.NORMAL) state = PetState.SLEEPING
        }
        LaunchedEffect(state) {
            if (state == PetState.PRESSED) {
                delay(650)
                if (state == PetState.PRESSED) {
                    state = PetState.NORMAL
                    activityToken++
                }
            }
        }

        val shownX = if (x.isNaN()) maxX else x.coerceIn(minX, maxX)
        val shownY = if (y.isNaN()) maxY else y.coerceIn(minY, maxY)
        val imageSize by animateDpAsState(
            targetValue = when (state) {
                PetState.SLEEPING -> 56.dp
                PetState.NORMAL -> 62.dp
                PetState.PRESSED -> 66.dp
                PetState.DRAGGING -> 64.dp
            },
            animationSpec = tween(140),
            label = "petSize"
        )
        val rotation by animateFloatAsState(
            targetValue = when (state) {
                PetState.SLEEPING -> 7f
                PetState.PRESSED -> -4f
                PetState.DRAGGING -> 4f
                PetState.NORMAL -> 0f
            },
            animationSpec = tween(140),
            label = "petRotation"
        )
        val interactionSource = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .offset { IntOffset(shownX.roundToInt(), shownY.roundToInt()) }
                .size(hitSize)
                .zIndex(4f)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragStart = { state = PetState.DRAGGING },
                        onDragEnd = { state = PetState.NORMAL; activityToken++ },
                        onDragCancel = { state = PetState.NORMAL; activityToken++ }
                    ) { change, dragAmount ->
                        change.consume()
                        x = (if (x.isNaN()) maxX else x).plus(dragAmount.x).coerceIn(minX, maxX)
                        y = (if (y.isNaN()) maxY else y).plus(dragAmount.y).coerceIn(minY, maxY)
                    }
                }
                .clickable(interactionSource = interactionSource, indication = null) {
                    state = PetState.PRESSED
                    activityToken++
                },
            contentAlignment = Alignment.Center
        ) {
            Mascot(
                mood = when (state) {
                    PetState.SLEEPING -> MascotMood.BLOCKED
                    PetState.PRESSED -> MascotMood.WELCOME
                    PetState.DRAGGING -> MascotMood.WORKING
                    PetState.NORMAL -> MascotMood.IDLE
                },
                contentDescription = when (state) {
                    PetState.SLEEPING -> "睡觉中的 GPT 娘宠物"
                    PetState.NORMAL -> "GPT 娘宠物"
                    PetState.PRESSED -> "被点击的 GPT 娘宠物"
                    PetState.DRAGGING -> "正在拖拽 GPT 娘宠物"
                },
                modifier = Modifier.rotate(rotation).alpha(if (state == PetState.SLEEPING) 0.82f else 1f),
                size = imageSize
            )
            if (state == PetState.SLEEPING) {
                Text(
                    "Zz",
                    modifier = Modifier.align(Alignment.TopEnd),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier.clip(MaterialTheme.shapes.small),
        contentScale = ContentScale.Fit
    )
}
