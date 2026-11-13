package yos.music.player.ui.widgets.effects

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.imageLoader
import coil.request.ImageRequest
import com.google.android.renderscript.Toolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundBass
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundBassSensitivity
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundBrightness
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundDetail
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundEffect
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundFps
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundGrain
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundSaturation
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundScale
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundSpeed
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundStyle
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundVignette
import yos.music.player.ui.widgets.effects.mesh.BassAnalyzer
import yos.music.player.ui.widgets.effects.mesh.CoverTexture
import yos.music.player.ui.widgets.effects.mesh.MeshGradientView
import yos.music.player.ui.widgets.effects.mesh.MeshSettings

/**
 * The playback page background: a mesh gradient made from the cover, ported from
 * Apple Music-like Lyrics (https://github.com/amll-dev/applemusic-like-lyrics, GNU AGPL-3.0).
 */
@Composable
fun YosFloatingLight(
    modifier: Modifier,
    album: () -> Uri?,
    isPlaying: () -> Boolean,
    showMiniPlayer: () -> Boolean
) {
    val context = LocalContext.current
    val rawCover = remember { mutableStateOf<IntArray?>(null) }
    val cover = remember { mutableStateOf<IntArray?>(null) }
    val viewState = remember { mutableStateOf<MeshGradientView?>(null) }

    LaunchedEffect(album()) {
        val uri = album()
        rawCover.value = if (uri == null) null else withContext(Dispatchers.IO) { loadCover(context, uri) }
    }

    // The color look is applied to the small cover, so a new saturation or brightness needs no new download
    val saturation = NowplayingBackgroundSaturation
    val brightness = NowplayingBackgroundBrightness
    LaunchedEffect(rawCover.value, saturation, brightness) {
        val raw = rawCover.value
        cover.value = if (raw == null) null else withContext(Dispatchers.Default) {
            CoverTexture.process(raw, saturation = saturation.toDouble(), brightness = brightness.toDouble())
        }
    }

    val settings = MeshSettings(
        renderScale = NowplayingBackgroundScale,
        fps = NowplayingBackgroundFps,
        flowSpeed = NowplayingBackgroundSpeed,
        detail = NowplayingBackgroundDetail,
        style = NowplayingBackgroundStyle,
        vignette = NowplayingBackgroundVignette,
        grain = NowplayingBackgroundGrain
    )

    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED) && !showMiniPlayer()
    val flowing = NowplayingBackgroundEffect && isPlaying() && active

    AndroidView(
        factory = { MeshGradientView(it).also { view -> viewState.value = view } },
        modifier = modifier,
        update = { view ->
            view.applySettings(settings)
            view.setCover(cover.value)
            view.setFlowing(flowing)
        }
    )

    // The bass needs the record audio permission. It only analyses the audio this player plays
    val sessionId = MediaController.audioSessionId.intValue
    val bass = flowing && NowplayingBackgroundBass && sessionId > 0 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    val view = viewState.value
    DisposableEffect(view, bass, sessionId) {
        if (view == null || !bass) {
            view?.setBassLevel(0f)
            onDispose { }
        } else {
            val analyzer = BassAnalyzer({ NowplayingBackgroundBassSensitivity }) { view.setBassLevel(it) }
            analyzer.start(sessionId)
            onDispose { analyzer.stop() }
        }
    }
}

/** The cover as a small square of pixels. The look of the background is applied to it later */
private suspend fun loadCover(context: Context, uri: Uri): IntArray? {
    val request = ImageRequest.Builder(context).data(uri).size(64).allowHardware(false).build()
    val bitmap = context.imageLoader.execute(request).drawable?.toBitmap() ?: return null
    val size = CoverTexture.SIZE
    val small = Bitmap.createScaledBitmap(bitmap, size, size, true)
    val pixels = IntArray(size * size)
    small.getPixels(pixels, 0, size, 0, 0, size, size)
    if (small !== bitmap) small.recycle()
    return pixels
}

fun imageResolve(image: Bitmap, moreLight: Boolean = false): Bitmap {
    var resizedBitmap = image.copy(Bitmap.Config.ARGB_8888, true)
    resizedBitmap.applyCanvas {
        val paint = Paint()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = true

        val saturationMatrix = ColorMatrix()
        saturationMatrix.setSaturation(3f)

        paint.colorFilter = ColorMatrixColorFilter(saturationMatrix)
        drawBitmap(resizedBitmap, 0f, 0f, paint)

        if (moreLight) {
            drawColor((0x1AFFFFFF).toInt())
            drawColor((0xFFFFFFFF).toInt(), PorterDuff.Mode.OVERLAY)
            drawColor((0x52FFFFFF).toInt())
            drawColor((0xBFFFFFFF).toInt(), PorterDuff.Mode.OVERLAY)
        } else {
            drawColor((0x33000000).toInt(), PorterDuff.Mode.OVERLAY)
            drawColor((0x40000000).toInt())
        }
    }
    resizedBitmap = Toolkit.blur(resizedBitmap, 25)
    return resizedBitmap
}