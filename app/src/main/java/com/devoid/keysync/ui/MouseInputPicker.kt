package com.devoid.keysync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.devoid.keysync.domain.mouseInputKeyCodes
import com.devoid.keysync.util.keyCodeToString

@Composable
fun MouseInputPicker(selected: Int, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("鼠标绑定：${selected.keyCodeToString()}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            mouseInputKeyCodes.forEach { code ->
                DropdownMenuItem(text = { Text(code.keyCodeToString()) },
                    onClick = { expanded = false; onPick(code) })
            }
        }
    }
}
