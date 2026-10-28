package com.jarves.mh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.network.HfModelFile
import com.jarves.mh.network.HfRepo
import com.jarves.mh.network.HfSection
import com.jarves.mh.network.HuggingFaceModels
import com.jarves.mh.network.ModelFit
import com.jarves.mh.network.ModelRecommender
import com.jarves.mh.ui.theme.PocketOrange

/** Everything the Hugging Face browser can ask the view model to do. */
class HuggingFaceActions(
    val section: (HfSection) -> Unit,
    val query: (String) -> Unit,
    val search: () -> Unit,
    val onlyFits: (Boolean) -> Unit,
    val onlyTools: (Boolean) -> Unit,
    val openRepo: (HfRepo) -> Unit,
    val back: () -> Unit,
    val scan: () -> Unit,
    val download: (HfModelFile) -> Unit,
    val saveToken: (String) -> Unit,
)

/** For screens and previews with no Hugging Face browser behind them. */
val NoHuggingFaceActions = HuggingFaceActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

private const val MAX_ROWS = 30

/**
 * Search and scan for GGUF models on Hugging Face, drawn inline in the page. It lives inside the
 * agent screen's scrolling list, so it uses plain rows (no nested lazy list, no pop-up window)
 * and keeps all of its data in the view model.
 */
@Composable
fun HuggingFaceBrowser(
    state: HuggingFaceBrowserState,
    ramGb: Int,
    downloading: Boolean,
    supportedArchitectures: Set<String>,
    actions: HuggingFaceActions,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val repo = state.openRepo
        val picks = state.recommendations
        if (repo != null || picks != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.hf_back)) }
                Text(
                    repo?.id ?: stringResource(R.string.hf_scan_title),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        DeviceLine(state, ramGb)

        if (repo == null && picks == null) {
            Button(onClick = actions.scan, enabled = !state.scanning, modifier = Modifier.fillMaxWidth()) {
                if (state.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(stringResource(R.string.hf_scan))
            }
            if (state.scanning) Text(stringResource(R.string.hf_scanning), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.query,
                singleLine = true,
                label = { Text(stringResource(R.string.hf_search_hint)) },
                trailingIcon = { IconButton(onClick = actions.search) { Icon(Icons.Default.Search, stringResource(R.string.hf_search)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { actions.search() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HfSection.entries.forEach { entry ->
                    FilterChip(
                        selected = state.section == entry,
                        onClick = { actions.section(entry) },
                        label = { Text(stringResource(sectionLabel(entry))) },
                    )
                }
            }
            CheckRow(stringResource(R.string.hf_only_fits), state.onlyFits, actions.onlyFits)
            CheckRow(stringResource(R.string.hf_only_tools), state.onlyTools, actions.onlyTools)
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (state.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)

        when {
            picks != null -> Recommendations(picks, ramGb, downloading, actions)
            repo != null -> {
                val runnable = ModelRecommender.runnable(repo, supportedArchitectures)
                if (!runnable) {
                    Text(stringResource(R.string.hf_wont_load_detail, repo.architecture.orEmpty()), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                if (!state.loading && state.files.isEmpty()) Text(stringResource(R.string.hf_no_files), fontSize = 13.sp)
                state.files.forEach { file -> FileRow(file, ramGb, enabled = !downloading && runnable) { actions.download(file) } }
            }
            else -> {
                val shown = state.repos.filter { item ->
                    (!state.onlyTools || item.supportsTools) &&
                        (!state.onlyFits || item.parameters == null ||
                            HuggingFaceModels.fit(HuggingFaceModels.estimatedQ4Bytes(item.parameters), ramGb) != ModelFit.TOO_BIG)
                }
                if (!state.loading && shown.isEmpty()) Text(stringResource(R.string.hf_no_results), fontSize = 13.sp)
                shown.take(MAX_ROWS).forEach { item ->
                    RepoRow(item, ramGb, ModelRecommender.runnable(item, supportedArchitectures)) { actions.openRepo(item) }
                }
                if (shown.size > MAX_ROWS) {
                    Text(stringResource(R.string.hf_more_results, MAX_ROWS, shown.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TokenBox(state.hasToken, actions.saveToken)
            }
        }
    }
}

@Composable
private fun DeviceLine(state: HuggingFaceBrowserState, ramGb: Int) {
    val profile = state.device
    Text(
        if (profile == null) stringResource(R.string.hf_device, ramGb) else stringResource(
            R.string.hf_device_profile,
            profile.ramGb,
            profile.fastCores,
            stringResource(
                when {
                    profile.int8Matmul -> R.string.hf_cpu_i8mm
                    profile.dotProd -> R.string.hf_cpu_dotprod
                    else -> R.string.hf_cpu_basic
                },
            ),
            profile.freeStorageBytes / 1e9,
        ),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Recommendations(
    picks: List<com.jarves.mh.network.ModelRecommendation>,
    ramGb: Int,
    downloading: Boolean,
    actions: HuggingFaceActions,
) {
    if (picks.isEmpty()) Text(stringResource(R.string.hf_scan_none), fontSize = 13.sp)
    picks.groupBy { it.section }.forEach { (group, items) ->
        Text(stringResource(sectionLabel(group)), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        items.forEach { pick ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(pick.repo.id, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                FileRow(pick.file, ramGb, enabled = !downloading) { actions.download(pick.file) }
            }
        }
    }
}

@Composable
private fun TokenBox(hasToken: Boolean, onSave: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (hasToken) R.string.hf_token_saved else R.string.hf_token_title), color = PocketOrange)
    }
    if (!expanded) return
    Text(stringResource(R.string.hf_token_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        singleLine = true,
        label = { Text(stringResource(R.string.hf_token_field)) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onSave(token); token = ""; expanded = false }, enabled = token.isNotBlank()) {
            Text(stringResource(R.string.hf_token_save))
        }
        if (hasToken) {
            TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.hf_token_remove), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun RepoRow(repo: HfRepo, ramGb: Int, runnable: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(repo.id, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                repo.parameters?.let { parameters ->
                    Text(stringResource(R.string.hf_params, parameters / 1e9), fontSize = 12.sp)
                    FitBadge(HuggingFaceModels.fit(HuggingFaceModels.estimatedQ4Bytes(parameters), ramGb))
                }
                if (repo.supportsTools) Badge(stringResource(R.string.hf_tools), PocketOrange)
                if (repo.gated) Badge(stringResource(R.string.hf_gated), MaterialTheme.colorScheme.error)
                if (!runnable) Badge(stringResource(R.string.hf_wont_load), MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.hf_downloads, repo.downloads), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FileRow(file: HfModelFile, ramGb: Int, enabled: Boolean, onDownload: () -> Unit) {
    val fit = HuggingFaceModels.fit(file.sizeBytes, ramGb)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.quant, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%.2f GB".format(file.sizeBytes / 1e9), fontSize = 12.sp)
                FitBadge(fit)
                // ARM phones have dedicated fast paths for Q4_0 in llama.cpp.
                if (file.quant == "Q4_0") Badge(stringResource(R.string.hf_fast_arm), PocketOrange)
                if (file.quant == "Q4_K_M") Badge(stringResource(R.string.hf_balanced), MaterialTheme.colorScheme.primary)
            }
        }
        OutlinedButton(onClick = onDownload, enabled = enabled && fit != ModelFit.TOO_BIG) { Text(stringResource(R.string.local_download)) }
    }
}

@Composable
private fun FitBadge(fit: ModelFit) = when (fit) {
    ModelFit.GOOD -> Badge(stringResource(R.string.hf_fit_good), Color(0xFF2E9E5B))
    ModelFit.TIGHT -> Badge(stringResource(R.string.hf_fit_tight), Color(0xFFE0A030))
    ModelFit.TOO_BIG -> Badge(stringResource(R.string.hf_fit_too_big), MaterialTheme.colorScheme.error)
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.16f)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private fun sectionLabel(section: HfSection): Int = when (section) {
    HfSection.CODING -> R.string.hf_section_coding
    HfSection.UNCENSORED -> R.string.hf_section_uncensored
    HfSection.SMALL -> R.string.hf_section_small
    HfSection.REASONING -> R.string.hf_section_reasoning
    HfSection.GENERAL -> R.string.hf_section_general
}
