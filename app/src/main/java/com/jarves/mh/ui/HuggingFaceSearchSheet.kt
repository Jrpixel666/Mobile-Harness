package com.jarves.mh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.network.HfModelFile
import com.jarves.mh.network.HfRepo
import com.jarves.mh.network.HfSection
import com.jarves.mh.network.HuggingFaceModels
import com.jarves.mh.network.ModelFit
import com.jarves.mh.ui.theme.PocketOrange
import kotlinx.coroutines.launch

/** Search Hugging Face for GGUF models and rate each against this phone's memory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFaceSearchSheet(
    ramGb: Int,
    downloading: Boolean,
    onSearch: suspend (query: String, onlyFits: Boolean) -> List<HfRepo>,
    onSearchSection: suspend (section: HfSection, onlyFits: Boolean) -> List<HfRepo>,
    onFiles: suspend (String) -> List<HfModelFile>,
    onDownload: (HfModelFile) -> Unit,
    hasToken: Boolean,
    onSaveToken: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf<HfSection?>(HfSection.CODING) }
    var tokenSaved by remember { mutableStateOf(hasToken) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var onlyFits by rememberSaveable { mutableStateOf(true) }
    var onlyTools by rememberSaveable { mutableStateOf(true) }
    var repos by remember { mutableStateOf<List<HfRepo>>(emptyList()) }
    var openRepo by remember { mutableStateOf<HfRepo?>(null) }
    var files by remember { mutableStateOf<List<HfModelFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        scope.launch {
            loading = true
            error = null
            openRepo = null
            val selected = section
            runCatching { if (selected != null) onSearchSection(selected, onlyFits) else onSearch(query, onlyFits) }
                .onSuccess { repos = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    fun open(repo: HfRepo) {
        scope.launch {
            loading = true
            error = null
            openRepo = repo
            files = emptyList()
            runCatching { onFiles(repo.id) }
                .onSuccess { files = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { search() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (openRepo != null) {
                    IconButton(onClick = { openRepo = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.hf_back)) }
                }
                Text(
                    openRepo?.id ?: stringResource(R.string.hf_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(stringResource(R.string.hf_device, ramGb), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (openRepo == null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.hf_search_hint)) },
                    trailingIcon = {
                        IconButton(onClick = { section = null; search() }) { Icon(Icons.Default.Search, stringResource(R.string.hf_search)) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { section = null; search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HfSection.entries.forEach { entry ->
                        FilterChip(
                            selected = section == entry,
                            onClick = { section = entry; query = ""; search() },
                            label = { Text(stringResource(sectionLabel(entry))) },
                        )
                    }
                }
                // Fitting is filtered by Hugging Face itself, so changing it searches again.
                FilterRow(stringResource(R.string.hf_only_fits), onlyFits) { onlyFits = it; search() }
                FilterRow(stringResource(R.string.hf_only_tools), onlyTools) { onlyTools = it }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val repo = openRepo
                if (repo == null) {
                    val shown = repos.filter { item ->
                        (!onlyTools || item.supportsTools) &&
                            (!onlyFits || item.parameters == null ||
                                HuggingFaceModels.fit(HuggingFaceModels.estimatedQ4Bytes(item.parameters), ramGb) != ModelFit.TOO_BIG)
                    }
                    if (!loading && shown.isEmpty()) item { Text(stringResource(R.string.hf_no_results), fontSize = 13.sp) }
                    items(shown, key = { it.id }) { item -> RepoRow(item, ramGb) { open(item) } }
                } else {
                    if (!loading && files.isEmpty()) item { Text(stringResource(R.string.hf_no_files), fontSize = 13.sp) }
                    items(files, key = { it.path }) { file -> FileRow(file, ramGb, enabled = !downloading) { onDownload(file); onDismiss() } }
                }
            }
            if (openRepo == null) {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(stringResource(if (tokenSaved) R.string.hf_token_saved else R.string.hf_token_title), color = PocketOrange)
                }
                if (showToken) {
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
                        OutlinedButton(
                            onClick = { onSaveToken(token); tokenSaved = token.isNotBlank(); token = ""; showToken = false },
                            enabled = token.isNotBlank(),
                        ) { Text(stringResource(R.string.hf_token_save)) }
                        if (tokenSaved) {
                            TextButton(onClick = { onSaveToken(""); tokenSaved = false }) {
                                Text(stringResource(R.string.hf_token_remove), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun FilterRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun RepoRow(repo: HfRepo, ramGb: Int, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(repo.id, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                repo.parameters?.let { parameters ->
                    Text(stringResource(R.string.hf_params, parameters / 1e9), fontSize = 12.sp)
                    FitBadge(HuggingFaceModels.fit(HuggingFaceModels.estimatedQ4Bytes(parameters), ramGb))
                }
                if (repo.supportsTools) Badge(stringResource(R.string.hf_tools), PocketOrange)
                if (repo.gated) Badge(stringResource(R.string.hf_gated), MaterialTheme.colorScheme.error)
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
