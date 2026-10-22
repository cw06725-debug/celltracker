package com.example.celltracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog

/** Common Test Setup surface. Business-specific controls stay inside their original test screens. */
@Composable
fun UnifiedTestSetupDialogV1270(
    title: String,
    onDismiss: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick=onDismiss) { Text("Cancel") }
        }
    },
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(title) { scrollState.scrollTo(0) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.90f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                Text("Test setup", style=MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
                    verticalArrangement=Arrangement.spacedBy(12.dp), content=content)
                footer()
            }
        }
    }
}
