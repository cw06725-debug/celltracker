package com.example.celltracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.90f).clip(RoundedCornerShape(28.dp))
            .background(bunnyHeroBrush())) {
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


/** Non-primary permission control shared by the test dialogs. It does not fake a grant. */
@Composable
fun SetupAccessibilityCardV130(
    serviceName: String,
    onOpenSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var version by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) version++
        }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }
    val ready = androidx.compose.runtime.remember(version, serviceName) {
        val enabled = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        }.getOrDefault("")
        enabled.split(':').any {
            it.substringBefore('/').equals(context.packageName, ignoreCase = true) &&
                it.substringAfter('/').substringAfterLast('.').equals(serviceName.substringAfterLast('.'), ignoreCase = true)
        }
    }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Accessibility", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(if (ready) "READY" else "NOT ENABLED",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Text("Required for automatic event detection.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Accessibility Settings", maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}


/** Shared gradient setup dialog for legacy TikTok flows; business logic stays unchanged. */
@Composable
fun GradientSetupAlertDialogV130(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.90f)
            .clip(RoundedCornerShape(28.dp)).background(bunnyHeroBrush())) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                title()
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    text()
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { dismissButton() }
                    Box(contentAlignment = Alignment.CenterEnd) { confirmButton() }
                }
            }
        }
    }
}

/** Reusable gradient primary action for new test and report screens. */
@Composable
fun GradientActionButtonV130(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val gradient = Brush.horizontalGradient(listOf(Color(0xFFA46AD4), Color(0xFF7777D6), Color(0xFF54C9BE)))
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp), color = Color.Transparent) {
        Box(Modifier.fillMaxWidth().background(if (enabled) gradient else Brush.horizontalGradient(
            listOf(Color(0xFFBDBAC8), Color(0xFFBDBAC8)))).padding(horizontal = 18.dp, vertical = 13.dp),
            contentAlignment = Alignment.Center) {
            Text(label, maxLines = 1, color = Color.White, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
