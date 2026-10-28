package com.jarves.mh.network

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One GitHub Actions run of a project's repository. */
data class GitHubRun(
    val id: Long,
    val number: Int,
    val title: String,
    val status: String,
    val conclusion: String?,
    val headSha: String,
    val url: String,
) {
    val isActive: Boolean get() = status != "completed"
    val failed: Boolean get() = conclusion in setOf("failure", "timed_out", "startup_failure")
}

/** An APK published by the build workflow as a release tagged apk-build-<n>. */
data class GitHubApkRelease(
    val tag: String,
    val buildNumber: Int,
    val assetName: String,
    val sizeBytes: Long,
)

/** What the GitHub panel knows about the open project's repository. */
data class GitHubProjectInfo(
    val repoFullName: String? = null,
    val branch: String = "",
    val changedFiles: Int = 0,
    val unpushedCommits: Int = 0,
    val hasBuildWorkflow: Boolean = false,
    val runs: List<GitHubRun> = emptyList(),
    val releases: List<GitHubApkRelease> = emptyList(),
)

object GitHubProjects {
    const val WORKFLOW_PATH = ".github/workflows/build-apk.yml"
    const val KEYSTORE_PATH = ".github/mobile-harness/debug.keystore"
    /** Release downloads land here; listed in .git/info/exclude so they are never committed. */
    const val DOWNLOAD_DIR = ".mobile-harness-downloads"

    private val RELEASE_TAG = Regex("^apk-build-(\\d+)$")
    private val REPO_PATH = Regex("^([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$")

    /** owner/name for a github.com remote URL, or null for any other host. */
    fun repoFromRemote(url: String): String? {
        val clean = url.trim()
        val path = when {
            clean.startsWith("git@github.com:") -> clean.removePrefix("git@github.com:")
            clean.startsWith("https://github.com/") -> clean.removePrefix("https://github.com/")
            clean.startsWith("ssh://git@github.com/") -> clean.removePrefix("ssh://git@github.com/")
            else -> return null
        }
        val match = REPO_PATH.matchEntire(path) ?: return null
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    /** A GitHub-safe repository name made from a project name. */
    fun repoName(projectName: String): String {
        val name = projectName.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-', '.')
            .take(80)
        return name.ifBlank { "my-app" }
    }

    fun isValidRepoName(name: String): Boolean =
        name.length in 1..100 && Regex("^[A-Za-z0-9._-]+$").matches(name) && name != "." && name != ".."

    /** Files with changes in `git status --porcelain` output. */
    fun changedFiles(porcelain: String): Int = porcelain.lineSequence().count { it.isNotBlank() }

    fun runs(json: String): List<GitHubRun> {
        val list = JSONObject(json).optJSONArray("workflow_runs") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val run = list.optJSONObject(index) ?: return@mapNotNull null
            GitHubRun(
                id = run.optLong("id"),
                number = run.optInt("run_number"),
                title = run.optString("display_title").ifBlank { run.optString("name") },
                status = run.optString("status"),
                conclusion = run.optString("conclusion").takeIf { !run.isNull("conclusion") && it.isNotBlank() },
                headSha = run.optString("head_sha"),
                url = run.optString("html_url"),
            )
        }
    }

    /** Newest first; only releases from the build workflow that carry an APK. */
    fun apkReleases(json: String): List<GitHubApkRelease> {
        val list = JSONArray(json)
        return (0 until list.length()).mapNotNull { index ->
            val release = list.optJSONObject(index) ?: return@mapNotNull null
            val tag = release.optString("tag_name")
            val number = RELEASE_TAG.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            val apk = (0 until assets.length()).mapNotNull(assets::optJSONObject)
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return@mapNotNull null
            GitHubApkRelease(tag, number, apk.optString("name"), apk.optLong("size"))
        }.sortedByDescending { it.buildNumber }
    }

    /** The end of a failed job's log, small enough to hand to the coding agent. */
    fun logTail(log: String, maxLines: Int = 150): String =
        log.lineSequence()
            // `gh run view --log-failed` prefixes each line with "job<TAB>step<TAB>".
            .map { line -> line.split('\t').drop(2).joinToString("\t").ifEmpty { line } }
            .toList()
            .takeLast(maxLines)
            .joinToString("\n")

    fun isAndroidProject(files: Set<String>): Boolean =
        files.any { it in setOf("settings.gradle.kts", "settings.gradle", "app/build.gradle.kts", "app/build.gradle") }

    /**
     * Builds a debug APK on every push and publishes it as a pre-release tagged
     * apk-build-<run number>, which the GitHub panel lists and installs. The project's
     * debug key is committed alongside, so builds from GitHub and from the phone are
     * signed alike and install over each other.
     */
    val ANDROID_BUILD_WORKFLOW = """
        name: Build APK

        on:
          push:
          workflow_dispatch:

        permissions:
          contents: write

        concurrency:
          group: build-apk-${'$'}{{ github.ref }}
          cancel-in-progress: true

        jobs:
          build:
            runs-on: ubuntu-latest
            timeout-minutes: 45
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: '17'

              - uses: gradle/actions/setup-gradle@v4
                with:
                  gradle-version: '8.14.3'

              - name: Use the project's debug key
                run: |
                  mkdir -p ~/.android
                  if [ -f $KEYSTORE_PATH ]; then
                    cp $KEYSTORE_PATH ~/.android/debug.keystore
                  fi

              - name: Build the APK
                run: |
                  if [ -x ./gradlew ]; then
                    ./gradlew assembleDebug --no-daemon
                  else
                    gradle assembleDebug --no-daemon
                  fi

              - name: Name the APK
                run: |
                  apk=${'$'}(find . -path '*/build/outputs/apk/*' -name '*.apk' | head -n 1)
                  if [ -z "${'$'}apk" ]; then echo "No APK was built"; exit 1; fi
                  cp "${'$'}apk" "${'$'}{{ github.event.repository.name }}-build-${'$'}{{ github.run_number }}.apk"

              - uses: actions/upload-artifact@v4
                with:
                  name: apk
                  path: '*-build-*.apk'

              - uses: softprops/action-gh-release@v2
                with:
                  tag_name: apk-build-${'$'}{{ github.run_number }}
                  name: Build ${'$'}{{ github.run_number }}
                  prerelease: true
                  files: '*-build-*.apk'
    """.trimIndent() + "\n"
}
