package com.devoid.keysync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devoid.keysync.R
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The device numbers that decide where a button ends up.
 *
 * [viewportPx] and [baselinePx] are here because they are the two values behind
 * almost every "my buttons are in the wrong place" report: when they disagree,
 * `projectLayout` rescales the saved layout on load, so what is drawn is not
 * what was saved. A card that only showed the display size would hide exactly
 * the mismatch the user is trying to see.
 */
internal data class DeviceInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val density: Float,
    val isLandscape: Boolean,
    val viewportPx: Offset?,
    val baselinePx: Offset?,
    val deviceModel: String,
    val androidRelease: String,
    val sdkInt: Int,
) {
    val widthDp: Int get() = toDp(widthPx)
    val heightDp: Int get() = toDp(heightPx)

    private fun toDp(px: Int): Int = if (density > 0f) (px / density).roundToInt() else px

    /**
     * Physical diagonal in inches. A display's diagonal does not change with
     * orientation, so this uses the raw pixel size and the reported dpi.
     */
    val diagonalInches: Float
        get() = if (densityDpi <= 0) {
            0f
        } else {
            sqrt(widthPx.toFloat() * widthPx + heightPx.toFloat() * heightPx) / densityDpi
        }

    /** True when the overlay window and the active profile's baseline disagree. */
    val baselineMismatch: Boolean
        get() = viewportPx != null && baselinePx != null &&
            (viewportPx.x != baselinePx.x || viewportPx.y != baselinePx.y)
}

/** "3.0x", always with a dot so the value reads the same in every locale. */
internal fun formatScale(density: Float): String =
    String.format(Locale.US, "%.1fx", density)

/** "-" when the diagonal cannot be derived, otherwise e.g. "5.48". */
internal fun formatInches(inches: Float): String =
    if (inches <= 0f) "-" else String.format(Locale.US, "%.2f", inches)

internal fun readDeviceInfo(
    context: Context,
    configuration: Configuration,
    viewport: Offset?,
    baseline: Offset?,
): DeviceInfo {
    val metrics = context.resources.displayMetrics
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    return DeviceInfo(
        widthPx = metrics.widthPixels,
        heightPx = metrics.heightPixels,
        densityDpi = metrics.densityDpi,
        density = metrics.density,
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        viewportPx = viewport,
        baselinePx = baseline,
        // "Xiaomi K100 Pro Max", but not "Xiaomi Xiaomi 14".
        deviceModel = if (manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        },
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
    )
}

/**
 * Plain-text form, for the clipboard.
 *
 * Users paste this into a bug report, so it stays greppable ASCII where it can
 * ("2400 x 1080 px") instead of using the typographic separators the card uses.
 */
internal fun DeviceInfo.toPlainText(context: Context): String = buildString {
    fun line(labelRes: Int, value: String) {
        appendLine("${context.getString(labelRes)}: $value")
    }

    appendLine(context.getString(R.string.device_info_title))
    line(R.string.device_info_label_physical, "$widthPx x $heightPx px")
    line(R.string.device_info_label_logical, "$widthDp x $heightDp dp")
    line(R.string.device_info_label_density, "$densityDpi dpi (${formatScale(density)})")
    line(R.string.device_info_label_diagonal, "${formatInches(diagonalInches)} in")
    line(
        R.string.device_info_label_orientation,
        context.getString(
            if (isLandscape) R.string.device_info_landscape else R.string.device_info_portrait
        ),
    )
    line(
        R.string.device_info_label_viewport,
        viewportPx?.let { "${it.x.toInt()} x ${it.y.toInt()} px" }
            ?: context.getString(R.string.device_info_value_viewport_unknown),
    )
    line(
        R.string.device_info_label_baseline,
        baselinePx?.let { "${it.x.toInt()} x ${it.y.toInt()} px" }
            ?: context.getString(R.string.device_info_value_baseline_unrecorded),
    )
    line(R.string.device_info_label_device, deviceModel)
    line(R.string.device_info_label_android, "Android $androidRelease (API $sdkInt)")
    if (baselineMismatch) appendLine(context.getString(R.string.device_info_mismatch))
}

/**
 * Screen resolution / dpi / overlay viewport, shown on the home screen.
 *
 * Collapsed it is one line; tapping it reveals the full set, and the copy
 * button puts the same numbers on the clipboard so they can be pasted into a
 * bug report without asking the user to transcribe them off the screen.
 */
@Composable
fun DeviceInfoCard(
    modifier: Modifier = Modifier,
    viewport: Offset?,
    baseline: Offset?,
    onCopied: () -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val info = remember(configuration, viewport, baseline) {
        readDeviceInfo(context, configuration, viewport, baseline)
    }
    var expanded by remember { mutableStateOf(false) }

    val orientationLabel = stringResource(
        if (info.isLandscape) R.string.device_info_landscape else R.string.device_info_portrait
    )

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.device_info_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(
                            R.string.device_info_summary,
                            info.widthPx,
                            info.heightPx,
                            info.densityDpi,
                            formatScale(info.density),
                            formatInches(info.diagonalInches),
                            orientationLabel
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    copyToClipboard(context, info.toPlainText(context))
                    onCopied()
                }) {
                    Text(stringResource(R.string.device_info_copy))
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
                    },
                    contentDescription = stringResource(
                        if (expanded) R.string.device_info_collapse else R.string.device_info_expand
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp, end = 10.dp)) {
                    DeviceInfoRow(
                        R.string.device_info_label_physical,
                        stringResource(
                            R.string.device_info_value_px,
                            info.widthPx,
                            info.heightPx
                        )
                    )
                    DeviceInfoRow(
                        R.string.device_info_label_logical,
                        stringResource(R.string.device_info_value_dp, info.widthDp, info.heightDp)
                    )
                    DeviceInfoRow(
                        R.string.device_info_label_density,
                        stringResource(
                            R.string.device_info_value_density,
                            info.densityDpi,
                            formatScale(info.density)
                        )
                    )
                    DeviceInfoRow(
                        R.string.device_info_label_diagonal,
                        stringResource(
                            R.string.device_info_value_inches,
                            formatInches(info.diagonalInches)
                        )
                    )
                    DeviceInfoRow(R.string.device_info_label_orientation, orientationLabel)
                    DeviceInfoRow(
                        R.string.device_info_label_viewport,
                        info.viewportPx?.let {
                            stringResource(R.string.device_info_value_px, it.x.toInt(), it.y.toInt())
                        } ?: stringResource(R.string.device_info_value_viewport_unknown)
                    )
                    DeviceInfoRow(
                        R.string.device_info_label_baseline,
                        info.baselinePx?.let {
                            stringResource(R.string.device_info_value_px, it.x.toInt(), it.y.toInt())
                        } ?: stringResource(R.string.device_info_value_baseline_unrecorded)
                    )
                    DeviceInfoRow(R.string.device_info_label_device, info.deviceModel)
                    DeviceInfoRow(
                        R.string.device_info_label_android,
                        stringResource(
                            R.string.device_info_value_android,
                            info.androidRelease,
                            info.sdkInt
                        )
                    )
                    if (info.baselineMismatch) {
                        Text(
                            modifier = Modifier.padding(top = 6.dp),
                            text = stringResource(R.string.device_info_mismatch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoRow(labelRes: Int, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.width(88.dp),
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            modifier = Modifier.weight(1f),
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("KeySync device info", text))
}
