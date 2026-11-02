package com.example.celltracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/** All recordings live in Reports. This page shows the summary BEFORE playback, not an XLSX. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingTimelineSummaryScreenV130(onBack:()->Unit) {
    val context=LocalContext.current
    val last=RecordingTimelineV130.last(context)
    val prefs=remember(context) { context.getSharedPreferences("ct_recording_timeline", android.content.Context.MODE_PRIVATE) }
    Column(Modifier.fillMaxSize().background(bunnyScreenBrush())) {
        TopAppBar(title={Text("Recording Summary")},navigationIcon={TextButton(onClick=onBack){Text("‹ Back")}})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(last==null) {
                Text("No timestamped recording available yet.")
            } else {
                val (_,session)=last
                Card(shape=RoundedCornerShape(25.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.Transparent)) {
                    Column(Modifier.fillMaxWidth().background(gradientCardBrushV130()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Latest recording",style=MaterialTheme.typography.titleLarge)
                        listOf(
                            "File" to prefs.getString("name", "").orEmpty(),
                            "Scenario" to prefs.getString("reviewKind", "").orEmpty().ifBlank { "Other recording" },
                            "Task" to prefs.getString("reviewTask", "").orEmpty(),
                            "Role" to prefs.getString("reviewRole", "").orEmpty(),
                            "Start time" to RecordingTimelineV130.clockFormat(session.wallAt(0)),
                            "End time" to RecordingTimelineV130.clockFormat(session.wallAt(session.videoDurationMs)),
                            "Duration" to String.format(Locale.US,"%.3f s",session.videoDurationMs/1000.0),
                            "Timeline source" to prefs.getString("mappingProvenance", "").orEmpty().ifBlank { "CELLTRACKER · ESTIMATED" },
                            "Time mapping" to "ESTIMATED (not frame-exact)"
                        ).filter { it.second.isNotBlank() }.forEach { (key,value) ->
                            Text("$key: $value",style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                GradientActionV130("PLAY / REVIEW RECORDING",{RecordingTimelinePlayerV130.open(context)},Modifier.fillMaxWidth())
                Text("The original MP4 is unchanged. Video timing should be calibrated with a visible reference event for precise modem-log correlation.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
