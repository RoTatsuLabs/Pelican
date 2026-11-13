package yos.music.player.ui.pages.settings.performance.userinterface

import android.content.Context
import androidx.annotation.StringRes
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import java.util.Locale
import kotlin.math.roundToInt

/** One row of the background settings: its texts, its range and where its value is kept */
class BackgroundSlider(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val min: Float,
    val max: Float,
    val step: Float,
    val default: Float,
    val get: () -> Float,
    val set: (Float) -> Unit,
    val format: (Float) -> String
)

private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

/** The sliders of the playback page background, in the order they are shown */
fun backgroundSliders(context: Context): List<BackgroundSlider> = listOf(
    BackgroundSlider(
        R.string.settings_nowplaying_bg_scale, R.string.settings_nowplaying_bg_scale_desc,
        0.25f, 1f, 0.05f, 0.5f,
        { SettingsLibrary.NowplayingBackgroundScale }, { SettingsLibrary.NowplayingBackgroundScale = it },
        ::percent
    ),
    BackgroundSlider(
        R.string.settings_nowplaying_bg_fps, R.string.settings_nowplaying_bg_fps_desc,
        10f, 60f, 5f, 30f,
        { SettingsLibrary.NowplayingBackgroundFps.toFloat() },
        { SettingsLibrary.NowplayingBackgroundFps = it.roundToInt() },
        { "${it.roundToInt()} FPS" }
    ),
    BackgroundSlider(
        R.string.settings_nowplaying_bg_speed, R.string.settings_nowplaying_bg_speed_desc,
        0.25f, 5f, 0.25f, 2f,
        { SettingsLibrary.NowplayingBackgroundSpeed }, { SettingsLibrary.NowplayingBackgroundSpeed = it },
        { String.format(Locale.US, "%.2f×", it) }
    ),
    BackgroundSlider(
        R.string.settings_nowplaying_bg_bass_sensitivity, R.string.settings_nowplaying_bg_bass_sensitivity_desc,
        0f, 2f, 0.05f, 1f,
        { SettingsLibrary.NowplayingBackgroundBassSensitivity },
        { SettingsLibrary.NowplayingBackgroundBassSensitivity = it },
        ::percent
    ),
    BackgroundSlider(
        R.string.settings_nowplaying_bg_detail, R.string.settings_nowplaying_bg_detail_desc,
        8f, 50f, 1f, 30f,
        { SettingsLibrary.NowplayingBackgroundDetail.toFloat() },
        { SettingsLibrary.NowplayingBackgroundDetail = it.roundToInt() },
        { "${it.roundToInt()}" }
    ),
    BackgroundSlider(
        R.string.settings_nowplaying_bg_saturation, R.string.settings_nowplaying_bg_saturation_desc,
        0f, 5f, 0.1f, 3f,
        { SettingsLibrary.NowplayingBackgroundSaturation }, { SettingsLibrary.NowplayingBackgroundSaturation = it },
        { String.format(Locale.US, "%.1f", it) }
    ),
    BackgroundSlider(
        R.string.settings_nowplaying_bg_brightness, R.string.settings_nowplaying_bg_brightness_desc,
        0.3f, 1.5f, 0.05f, 0.75f,
        { SettingsLibrary.NowplayingBackgroundBrightness }, { SettingsLibrary.NowplayingBackgroundBrightness = it },
        ::percent
    ),
    BackgroundSlider(
        R.string.settings_nowplaying_bg_style, R.string.settings_nowplaying_bg_style_desc,
        0f, 7f, 1f, 0f,
        { SettingsLibrary.NowplayingBackgroundStyle.toFloat() },
        { SettingsLibrary.NowplayingBackgroundStyle = it.roundToInt() },
        {
            when (val style = it.roundToInt()) {
                0 -> context.getString(R.string.settings_nowplaying_bg_style_random)
                7 -> context.getString(R.string.settings_nowplaying_bg_style_generated)
                else -> context.getString(R.string.settings_nowplaying_bg_style_preset, style)
            }
        }
    ),
    BackgroundSlider(
        R.string.settings_nowplaying_bg_vignette, R.string.settings_nowplaying_bg_vignette_desc,
        0f, 1.5f, 0.05f, 1f,
        { SettingsLibrary.NowplayingBackgroundVignette }, { SettingsLibrary.NowplayingBackgroundVignette = it },
        ::percent
    ),
    BackgroundSlider(
        R.string.settings_nowplaying_bg_grain, R.string.settings_nowplaying_bg_grain_desc,
        0f, 20f, 1f, 1f,
        { SettingsLibrary.NowplayingBackgroundGrain }, { SettingsLibrary.NowplayingBackgroundGrain = it },
        { "${it.roundToInt()}×" }
    )
)
