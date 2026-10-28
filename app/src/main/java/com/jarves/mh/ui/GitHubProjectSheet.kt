package com.jarves.mh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.network.GitHubApkRelease
import com.jarves.mh.network.GitHubProjects
import com.jarves.mh.network.GitHubRun
import com.jarves.mh.ui.theme.PocketOrange
import kotlinx.coroutines.delay

/** Everything the GitHub panel can ask the view model to do. */
class GitHubProjectActions(
    val refresh: () -> Unit,
    val signIn: () -> Unit,
    val newCode: () -> Unit,
    val createRepository: (name: String, private: Boolean, addWorkflow: Boolean) -> Unit,
    val commitAndPush: (message: String) -> Unit,
    val addWorkflow: () -> Unit,
    val startBuild: () -> Unit,
    val fixBuild: (GitHubRun) -> Unit,
    val install: (GitHubApkRelease) -> Unit,
    val dismissMessage: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubProjectSheet(
    state: AppUiState,
    isAndroidProject: Boolean,
    actions: GitHubProjectActions,
    onDismiss: () -> Unit,
) {
    val info = state.githubProject
    val connected = state.githubAuthStatus == GitHubAuthStatus.CONNECTED
    val busy = state.githubProjectBusy

    LaunchedEffect(connected) { if (connected) actions.refresh() }
    // Follow running builds; a fresh push shows its run within a few seconds.
    val polling = info.runs.any(GitHubRun::isActive) || busy == "push" || busy == "workflow" || busy == "build"
    LaunchedEffect(polling, info.repoFullName) {
        while (polling && info.repoFullName != null) {
            delay(15_000)
            actions.refresh()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.gh_panel_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    info.repoFullName?.let {
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (connected) {
                    if (state.githubProjectLoading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = actions.refresh) { Icon(Icons.Default.Refresh, stringResource(R.string.gh_refresh)) }
                    }
                }
            }

            state.githubProjectMessage?.let { message ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (state.githubProjectError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = actions.dismissMessage),
                ) {
                    Text(message, Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }

            when {
                !connected -> SignInSection(state, actions)
                info.repoFullName == null -> CreateRepositorySection(state, isAndroidProject, actions)
                else -> {
                    RepositorySection(state, actions)
                    HorizontalDivider()
                    BuildsSection(state, isAndroidProject, actions)
                    if (info.releases.isNotEmpty()) {
                        HorizontalDivider()
                        ReleasesSection(state, actions)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SignInSection(state: AppUiState, actions: GitHubProjectActions) {
    Text(stringResource(R.string.gh_signin_desc), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (state.githubAuthStatus) {
        GitHubAuthStatus.STARTING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        GitHubAuthStatus.AWAITING_USER -> {
            Text(stringResource(R.string.ws_github_enter_code), fontSize = 13.sp)
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    state.githubUserCode.orEmpty(),
                    Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = 2.sp,
                )
            }
            OutlinedButton(onClick = actions.newCode, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ws_github_new_code)) }
        }
        else -> {
            state.githubMessage?.takeIf { state.githubAuthStatus == GitHubAuthStatus.ERROR }?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = actions.signIn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ws_github_signin)) }
        }
    }
}

@Composable
private fun CreateRepositorySection(state: AppUiState, isAndroidProject: Boolean, actions: GitHubProjectActions) {
    val defaultName = remember(state.activeProject?.id) { GitHubProjects.repoName(state.activeProject?.name.orEmpty()) }
    var name by rememberSaveable(state.activeProject?.id) { mutableStateOf(defaultName) }
    var private by rememberSaveable { mutableStateOf(true) }
    var addWorkflow by rememberSaveable { mutableStateOf(isAndroidProject) }
    Text(stringResource(R.string.gh_no_repo_title), fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.gh_no_repo_desc), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(100) },
        label = { Text(stringResource(R.string.gh_repo_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    CheckRow(stringResource(R.string.gh_private), private) { private = it }
    if (isAndroidProject) CheckRow(stringResource(R.string.gh_add_workflow), addWorkflow) { addWorkflow = it }
    Button(
        onClick = { actions.createRepository(name, private, isAndroidProject && addWorkflow) },
        enabled = state.githubProjectBusy == null && GitHubProjects.isValidRepoName(name.trim()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.githubProjectBusy == "create") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
        else Text(stringResource(R.string.gh_create_repo))
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun RepositorySection(state: AppUiState, actions: GitHubProjectActions) {
    val info = state.githubProject
    val uriHandler = LocalUriHandler.current
    var message by rememberSaveable(state.activeProject?.id) { mutableStateOf("") }
    if (info.branch.isNotBlank()) Text(stringResource(R.string.gh_branch, info.branch), fontSize = 13.sp)
    Text(
        stringResource(R.string.gh_changes, info.changedFiles, info.unpushedCommits),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = message,
        onValueChange = { message = it.take(200) },
        label = { Text(stringResource(R.string.gh_commit_message)) },
        placeholder = { Text(stringResource(R.string.gh_default_commit_message)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { actions.commitAndPush(message); message = "" },
        enabled = state.githubProjectBusy == null && (info.changedFiles > 0 || info.unpushedCommits > 0),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.githubProjectBusy == "push") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
        else Text(stringResource(R.string.gh_commit_push))
    }
    TextButton(onClick = { runCatching { uriHandler.openUri("https://github.com/${info.repoFullName}") } }) {
        Text(stringResource(R.string.gh_view_on_github), color = PocketOrange)
    }
}

@Composable
private fun BuildsSection(state: AppUiState, isAndroidProject: Boolean, actions: GitHubProjectActions) {
    val info = state.githubProject
    val busy = state.githubProjectBusy
    Text(stringResource(R.string.gh_builds), fontWeight = FontWeight.SemiBold)
    if (!info.hasBuildWorkflow && info.runs.isEmpty()) {
        Text(stringResource(R.string.gh_no_workflow), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isAndroidProject) {
            OutlinedButton(onClick = actions.addWorkflow, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                if (busy == "workflow") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.gh_add_workflow_button))
            }
        }
        return
    }
    if (info.runs.isEmpty()) {
        Text(stringResource(R.string.gh_no_builds), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    info.runs.forEach { run ->
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.gh_build_number, run.number), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val (label, color) = runStatus(run)
                    Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Text(run.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (run.failed) {
                    OutlinedButton(
                        onClick = { actions.fixBuild(run) },
                        enabled = busy == null && !state.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy == "fix-${run.id}") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.gh_fix_with_agent))
                    }
                }
            }
        }
    }
    if (info.hasBuildWorkflow) {
        OutlinedButton(onClick = actions.startBuild, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
            if (busy == "build") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.gh_start_build))
        }
    }
}

@Composable
private fun runStatus(run: GitHubRun): Pair<String, Color> = when {
    run.status == "queued" || run.status == "waiting" || run.status == "pending" ->
        stringResource(R.string.gh_status_queued) to MaterialTheme.colorScheme.onSurfaceVariant
    run.isActive -> stringResource(R.string.gh_status_running) to PocketOrange
    run.conclusion == "success" -> stringResource(R.string.gh_status_success) to Color(0xFF2E9E5B)
    run.failed -> stringResource(R.string.gh_status_failed) to MaterialTheme.colorScheme.error
    else -> stringResource(R.string.gh_status_cancelled) to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ReleasesSection(state: AppUiState, actions: GitHubProjectActions) {
    Text(stringResource(R.string.gh_releases), fontWeight = FontWeight.SemiBold)
    state.githubProject.releases.take(3).forEach { release ->
        Button(
            onClick = { actions.install(release) },
            enabled = state.githubProjectBusy == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.githubProjectBusy == "install-${release.buildNumber}") {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(stringResource(R.string.gh_install_build, release.buildNumber, formatMegabytes(release.sizeBytes)))
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)
