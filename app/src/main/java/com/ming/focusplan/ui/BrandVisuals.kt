package com.ming.focusplan.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ming.focusplan.R

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
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 24.dp, y = (-28).dp),
            size = 172.dp,
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
    val targetAlpha = if (subdued) 0.07f else 1f
    val alpha by animateFloatAsState(targetAlpha, tween(220), label = "mascotAlpha")
    Image(
        painter = painterResource(mood.drawableRes()),
        contentDescription = contentDescription,
        modifier = modifier.size(size).alpha(alpha),
        contentScale = ContentScale.Fit
    )
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
