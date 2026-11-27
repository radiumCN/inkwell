package com.radium.inkwell.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class UpdateFailureTest {
    private var githubStatus = 403
    private var body = ""
    private val headers = mutableMapOf<String, String>()
    private var deadline = 0L
    private val paths = mutableListOf<String>()
    private var mirrorStatus = 200
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val github = request.url.host == "api.github.com"
        paths += request.url.encodedPath
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (github) githubStatus else mirrorStatus).message("test")
            .apply { if (github) headers.forEach { (name, value) -> header(name, value) } }
            .body((if (github) body else """{"version":"v0.6.3-beta.16","has_update":true,
                "asset":{"filename":"app.apk","size":1,"sha256":"test","download_url":"https://mirror.example/app.apk"}}""").toResponseBody())
            .build()
    }.build()

    private fun manager() = UpdateManager(
        UpdateChecker(http), ServerUpdateChecker(http, "https://mirror.example"),
        UpdateInstaller(http) { error("检查版本不应安装") }, { deadline }, { deadline = it },
    )

    private suspend fun UpdateManager.checkGithub() =
        check(UpdateSource.GITHUB, UpdateChannel.BETA, "0.6.3-beta.15", "manual")

    @Test fun forbiddenAllowsOneTimeMirrorWithoutRateLimitingGithub() = runBlocking {
        headers["CF-Mitigated"] = "challenge"
        val manager = manager()
        val denied = assertIs<CheckResult.Failed>(manager.checkGithub())
        assertEquals(403, denied.httpStatus)
        assertEquals("edge_challenge", denied.responseMarker)
        assertNull(denied.retryAt)
        assertEquals(0L, deadline)
        val other = manager.check(UpdateSource.SERVER, UpdateChannel.BETA, "0.6.3-beta.15", "fallback")
        assertEquals("0.6.3-beta.16", assertIs<CheckResult.Available>(other).info.latestVersion)
        mirrorStatus = 503
        assertEquals(503, assertIs<CheckResult.Failed>(manager.check(UpdateSource.SERVER, UpdateChannel.BETA, "0.6.3-beta.15", "fallback")).httpStatus)
        manager.checkGithub()
        assertEquals(listOf("/repos/radiumCN/inkwell/releases", "/api/v1/update/beta", "/api/v1/update/beta", "/repos/radiumCN/inkwell/releases"), paths)
    }

    @Test fun exhaustedQuotaSurvivesRestartAndDoesNotBlockMirror() = runBlocking {
        val reset = System.currentTimeMillis() / 1000 + 300
        headers["X-RateLimit-Remaining"] = "0"
        headers["X-RateLimit-Reset"] = reset.toString()
        headers["X-GitHub-Request-Id"] = "test-id"
        val failed = assertIs<CheckResult.Failed>(manager().checkGithub())
        assertEquals("remaining_zero", failed.limitSignal)
        assertEquals("github_request_id", failed.responseMarker)
        assertEquals(reset * 1000, deadline)
        val restarted = manager()
        val held = restarted.check(UpdateSource.GITHUB, UpdateChannel.STABLE, "0.6.3", "launch")
        assertEquals("cooldown", assertIs<CheckResult.Failed>(held).limitSignal)
        restarted.check(UpdateSource.SERVER, UpdateChannel.BETA, "0.6.3-beta.15", "fallback")
        assertEquals(2, paths.size)
        deadline = 1L
        assertEquals("remaining_zero", assertIs<CheckResult.Failed>(manager().checkGithub()).limitSignal)
        assertEquals(3, paths.size)
    }

    @Test fun secondaryRateLimitAndRetryAfterAreReported() = runBlocking {
        body = """{"message":"You have exceeded a secondary rate limit"}"""
        assertEquals("api_message", assertIs<CheckResult.Failed>(manager().checkGithub()).limitSignal)
        deadline = 0L
        githubStatus = 429
        headers["Retry-After"] = "120"
        val start = System.currentTimeMillis()
        val failure = assertIs<CheckResult.Failed>(manager().checkGithub())
        assertEquals("retry_after", failure.limitSignal)
        assertTrue(deadline >= start + 120_000)
    }
}
