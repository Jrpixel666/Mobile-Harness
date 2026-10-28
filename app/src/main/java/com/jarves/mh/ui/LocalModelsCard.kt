package com.jarves.mh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.ui.theme.PocketOrange

/** Download, pick and remove the GGUF models the on-device provider runs. */
@Composable
fun LocalModelsCard(
    state: AppUiState,
    selectedModel: String,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleHuggingFace: () -> Unit,
    huggingFaceActions: HuggingFaceActions,
    supportedArchitectures: Set<String> = emptySet(),
) {
    LaunchedEffect(Unit) { onRefresh() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.local_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.local_desc, state.deviceRamGb),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.localModels.forEach { status ->
                val model = status.model
                val downloading = state.localModelDownloading == model.id
                val tooBig = state.deviceRamGb in 1 until model.minRamGb
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = status.downloaded) { onSelect(model.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedModel == model.id,
                        onClick = { onSelect(model.id) },
                        enabled = status.downloaded,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(model.title, fontWeight = FontWeight.Medium)
                        if (model.custom) Text(stringResource(R.string.local_from_hf), fontSize = 11.sp, color = PocketOrange)
                        Text(
                            stringResource(
                                if (tooBig) R.string.local_size_warning else R.string.local_size,
                                "%.1f".format(model.sizeBytes / 1e9),
                                model.minRamGb,
                            ),
                            fontSize = 11.sp,
                            color = if (tooBig) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        status.downloaded -> TextButton(onClick = { onDelete(model.id) }) {
                            Text(stringResource(R.string.local_delete), color = MaterialTheme.colorScheme.error)
                        }
                        downloading -> Text("${(state.localModelProgress * 100).toInt()}%", color = PocketOrange, fontSize = 13.sp)
                        else -> OutlinedButton(
                            onClick = { onDownload(model.id) },
                            enabled = state.localModelDownloading == null,
                        ) { Text(stringResource(R.string.local_download)) }
                    }
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { state.localModelProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = PocketOrange,
                    )
                }
            }
            OutlinedButton(onClick = onToggleHuggingFace, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.huggingFace.open) R.string.hf_close else R.string.hf_open))
            }
            if (state.huggingFace.open) {
                HuggingFaceBrowser(
                    state = state.huggingFace,
                    ramGb = state.deviceRamGb,
                    downloading = state.localModelDownloading != null,
                    supportedArchitectures = supportedArchitectures,
                    actions = huggingFaceActions,
                )
            }
            state.localModelMessage?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
