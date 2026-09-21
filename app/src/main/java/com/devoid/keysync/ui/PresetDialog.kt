package com.devoid.keysync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.devoid.keysync.R
import com.devoid.keysync.data.mapping.MappingPreset

/**
 * Picks one bundled keyboard layout to apply.
 *
 * The dialog used to ship with no dismiss button at all, so the only way out
 * was the back gesture or tapping outside — neither was discoverable next to
 * a column of "应用" buttons.
 */
@Composable
fun PresetDialog(
    title: String,
    presets: List<MappingPreset>,
    onDismiss: () -> Unit,
    onApply: (MappingPreset) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (presets.isEmpty()) {
                Text(stringResource(R.string.main_preset_empty))
            } else {
                Column {
                    Text(
                        modifier = Modifier.padding(bottom = 8.dp),
                        text = stringResource(R.string.main_preset_replace_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        presets.groupBy { it.category.lowercase() }
                            .forEach { (category, entries) ->
                                item(key = "header-$category") {
                                    Text(
                                        text = category.uppercase(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                }
                                items(entries, key = { it.id }) { preset ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp)
                                        ) {
                                            Text(
                                                preset.name,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            if (preset.description.isNotBlank()) {
                                                Text(
                                                    preset.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        OutlinedButton(onClick = { onApply(preset) }) {
                                            Text(stringResource(R.string.main_preset_apply))
                                        }
                                    }
                                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
