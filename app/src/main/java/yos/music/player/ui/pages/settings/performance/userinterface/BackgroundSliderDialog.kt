package yos.music.player.ui.pages.settings.performance.userinterface

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.alexzhirkevich.cupertino.CupertinoSlider
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.OptionDialog
import kotlin.math.roundToInt

/** A bottom sheet with one slider, shaped like the screen corner dialog. It shows the value as it changes */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderSettingDialog(
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    default: Float,
    format: (Float) -> String,
    saveText: String,
    resetText: String,
    onSave: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogProperties = ModalBottomSheetProperties(
        securePolicy = SecureFlagPolicy.Inherit,
        isFocusable = true,
        shouldDismissOnBackPress = false
    )
    val bottomSheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val current = remember { mutableFloatStateOf(value) }
    val context = LocalContext.current

    fun snap(raw: Float) =
        (((raw - range.start) / step).roundToInt() * step + range.start).coerceIn(range.start, range.endInclusive)

    fun close(save: Float) {
        scope.launch { bottomSheetState.hide() }.invokeOnCompletion {
            onDismiss()
            onSave(save)
        }
    }

    OptionDialog(
        icon = {
            Text(
                text = format(current.floatValue),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        title = title,
        subTitle = description,
        properties = dialogProperties,
        bottomSheetState = bottomSheetState,
        content = {
            val interactionSource = remember { MutableInteractionSource() }
            CupertinoTheme {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            color = (Color.LightGray withNight Color.DarkGray).copy(alpha = 0.15f),
                            shape = YosRoundedCornerShape(14.dp)
                        )
                        .padding(vertical = 15.dp, horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_tips_minus), contentDescription = null,
                        modifier = Modifier
                            .size(12.dp)
                            .alpha(0.45f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    Vibrator.click(context)
                                    current.floatValue = snap(current.floatValue - step)
                                })
                    )
                    CupertinoSlider(
                        value = current.floatValue,
                        onValueChange = { current.floatValue = snap(it) },
                        thumb = {
                            Spacer(
                                Modifier
                                    .size(23.dp)
                                    .hoverable(interactionSource = interactionSource)
                                    .shadow(8.dp, CircleShape, clip = false, spotColor = Color.Black.copy(alpha = 0.55f))
                                    .background(Color.White, CircleShape)
                            )
                        },
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(end = 8.dp, start = 12.dp),
                        valueRange = range
                    )
                    Icon(
                        painter = painterResource(id = R.drawable.ic_tips_plus), contentDescription = null,
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .size(14.dp)
                            .alpha(0.45f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    Vibrator.click(context)
                                    current.floatValue = snap(current.floatValue + step)
                                })
                    )
                }
            }
        },
        positiveContent = saveText,
        negativeContent = resetText,
        onPositive = { close(current.floatValue) },
        onNegative = { close(default) }
    ) {
        onDismiss()
    }
}
