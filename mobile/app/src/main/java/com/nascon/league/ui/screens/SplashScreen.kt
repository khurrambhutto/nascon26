package com.nascon.league.ui.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.nascon.league.R
import com.nascon.league.ui.theme.Pitch

/** Plays the bundled wave reveal once, then opens sign-in or the saved session. */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context as? android.app.Activity
        activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        } }
        onDispose { activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        } } }
    }
    // A decoder failure or an OS animation setting must never trap the user at launch.
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(5_000); finish() }
    Box(Modifier.fillMaxSize().background(Pitch.Turf).testTag("startup_splash"), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.sizeIn(maxWidth = 400.dp, maxHeight = 400.dp).fillMaxSize(),
            factory = { ctx ->
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        try {
                            val image = ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, R.raw.arenaone_wave_splash))
                            setImageDrawable(image)
                            if (image is AnimatedImageDrawable) {
                                image.repeatCount = 0
                                image.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                                    override fun onAnimationEnd(drawable: Drawable?) { post { finish() } }
                                })
                                image.start()
                            } else post { finish() }
                        } catch (_: Exception) { post { finish() } }
                    }
                } else LegacyGifView(ctx) { finish() }
            },
            onRelease = { view ->
                if (Build.VERSION.SDK_INT >= 28) ((view as? ImageView)?.drawable as? AnimatedImageDrawable)?.apply {
                    stop(); clearAnimationCallbacks()
                }
            },
        )
    }
}

/** Android 26/27 have Movie but not ImageDecoder. No extra GIF dependency needed. */
@Suppress("DEPRECATION")
private class LegacyGifView(context: Context, private val onFinished: () -> Unit) : View(context) {
    private val movie = context.resources.openRawResource(R.raw.arenaone_wave_splash).use(Movie::decodeStream)
    private val started = SystemClock.uptimeMillis()
    private var finished = false
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val gif = movie ?: run { if (!finished) { finished = true; post(onFinished) }; return }
        val duration = gif.duration().takeIf { it > 0 } ?: 3_170
        val elapsed = (SystemClock.uptimeMillis() - started).toInt()
        gif.setTime(minOf(elapsed, duration - 1))
        val scale = minOf(width.toFloat() / gif.width(), height.toFloat() / gif.height())
        canvas.save()
        canvas.translate((width - gif.width() * scale) / 2, (height - gif.height() * scale) / 2)
        canvas.scale(scale, scale); gif.draw(canvas, 0f, 0f); canvas.restore()
        if (elapsed < duration) postInvalidateOnAnimation()
        else if (!finished) { finished = true; post(onFinished) }
    }
}
