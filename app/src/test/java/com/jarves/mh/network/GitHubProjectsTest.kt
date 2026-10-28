package com.jarves.mh.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProjectsTest {
    @Test
    fun readsGitHubRemotes() {
        assertEquals("octo/app", GitHubProjects.repoFromRemote("https://github.com/octo/app.git"))
        assertEquals("octo/app", GitHubProjects.repoFromRemote("https://github.com/octo/app"))
        assertEquals("octo/my.app", GitHubProjects.repoFromRemote("git@github.com:octo/my.app.git"))
        assertNull(GitHubProjects.repoFromRemote("https://gitlab.com/octo/app.git"))
        assertNull(GitHubProjects.repoFromRemote("https://github.com/octo"))
    }

    @Test
    fun makesSafeRepoNames() {
        assertEquals("habit-tracker", GitHubProjects.repoName("  Habit Tracker!! "))
        assertEquals("my-app", GitHubProjects.repoName("***"))
        assertTrue(GitHubProjects.isValidRepoName("habit-tracker"))
        assertFalse(GitHubProjects.isValidRepoName("bad name"))
        assertFalse(GitHubProjects.isValidRepoName(".."))
    }

    @Test
    fun parsesRuns() {
        val runs = GitHubProjects.runs(
            """{"workflow_runs":[
              {"id":7,"run_number":3,"display_title":"Fix","status":"completed","conclusion":"failure","head_sha":"abc","html_url":"u"},
              {"id":8,"run_number":4,"display_title":"Next","status":"in_progress","conclusion":null,"head_sha":"def","html_url":"v"}
            ]}""",
        )
        assertEquals(2, runs.size)
        assertTrue(runs[0].failed)
        assertFalse(runs[0].isActive)
        assertNull(runs[1].conclusion)
        assertTrue(runs[1].isActive)
    }

    @Test
    fun keepsOnlyBuildReleasesWithAnApk() {
        val releases = GitHubProjects.apkReleases(
            """[
              {"tag_name":"apk-build-2","assets":[{"name":"app-build-2.apk","size":10}]},
              {"tag_name":"v1.0","assets":[{"name":"app.apk","size":10}]},
              {"tag_name":"apk-build-5","assets":[{"name":"notes.txt","size":1},{"name":"app-build-5.apk","size":20}]},
              {"tag_name":"apk-build-6","assets":[]}
            ]""",
        )
        assertEquals(listOf(5, 2), releases.map { it.buildNumber })
        assertEquals("app-build-5.apk", releases[0].assetName)
    }

    @Test
    fun trimsFailedLogPrefixes() {
        val log = "build\tBuild the APK\tline one\nbuild\tBuild the APK\te: error here"
        assertEquals("line one\ne: error here", GitHubProjects.logTail(log))
        assertEquals("e: error here", GitHubProjects.logTail(log, maxLines = 1))
    }

    @Test
    fun workflowPublishesBuildReleases() {
        val workflow = GitHubProjects.ANDROID_BUILD_WORKFLOW
        assertTrue("tag_name: apk-build-\${{ github.run_number }}" in workflow)
        assertTrue(GitHubProjects.KEYSTORE_PATH in workflow)
        assertFalse("\$KEYSTORE_PATH" in workflow)
    }
}
