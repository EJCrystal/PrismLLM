package dev.soven.prismllm.antigravity

import dev.soven.prismllm.config.AntigravityAgyConfig
import dev.soven.prismllm.shared.error.GatewayError
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("component")
@Timeout(20)
class AntigravityAgyLoginServiceTest {
    @TempDir
    lateinit var root: Path

    private val url: String get() = "https://accounts.google.com/o/oauth2/v2/auth?client_id=fixture.apps.googleusercontent.com" +
        "&response_type=code&redirect_uri=" + URLEncoder.encode("https://antigravity.google/oauth-callback", Charsets.UTF_8)

    @Test
    fun officialCallbackAndUniqueParametersAreRequired() {
        assertTrue(validAgyAuthorizationUrl(url))
        listOf(url.replace("accounts.google.com", "evil.invalid"), url + "&response_type=token",
            url.replace("https://accounts", "http://accounts"), url + "#fragment").forEach {
            assertFalse(validAgyAuthorizationUrl(it))
        }
    }

    @Test
    fun loginPersistsOnlyMetadataAndNeverEnablesRoutesOrImportsOldTokens() = runBlocking {
        val configuration = config()
        var inspections = 0
        AntigravityAgyLoginService(configuration) {
            inspections++
            AcpPilotCatalog(listOf("fixture-model"), "fixture-model", "PRO")
        }.use { service ->
            val login = service.start("admin", "My subscription", "legacy-account")
            waitFor(service, login.loginId, "AWAITING_CODE")
            assertEquals(url, service.get(login.loginId, "admin").authorizeUrl)
            assertFailsWith<GatewayError> { service.get(login.loginId, "someone-else") }
            assertFailsWith<GatewayError> { service.submit(login.loginId, "admin", "code\nsecond-command") }
            assertFailsWith<GatewayError> { service.start("admin", "duplicate", null) }
            service.submit(login.loginId, "admin", "fixture-code")
            assertFailsWith<GatewayError> { service.submit(login.loginId, "admin", "fixture-code") }
            waitFor(service, login.loginId, "AUTHENTICATED")
            val account = service.list().single()
            assertEquals("legacy-account", account.sourceAccountId)
            assertEquals("PRO", account.subscriptionTier)
            assertFalse(account.routingEnabled)
            assertEquals(1, inspections)
            val stored = Files.readString(configuration.stateDir.resolve("accounts.json"))
            listOf("fixture-code", "authorizeUrl", "refresh_token", "access_token").forEach { assertFalse(it in stored) }
        }
        AntigravityAgyLoginService(configuration).use { service ->
            assertEquals("My subscription", service.list().single().name)
        }
    }

    @Test
    fun reauthorizationReusesTheSameCliHomeAndKeepsOneAccountRecord() = runBlocking {
        val directories = mutableListOf<Path>()
        AntigravityAgyLoginService(config()) { directory ->
            directories.add(directory)
            AcpPilotCatalog(listOf("fixture-model"), "fixture-model", "PRO")
        }.use { service ->
            val first = service.start("admin", "subscription", "legacy-account")
            waitFor(service, first.loginId, "AWAITING_CODE")
            service.submit(first.loginId, "admin", "fixture-code")
            waitFor(service, first.loginId, "AUTHENTICATED")
            delay(50)
            val profile = service.list().single()
            val second = service.start("admin", null, null, profile.id)
            waitFor(service, second.loginId, "AWAITING_CODE")
            service.submit(second.loginId, "admin", "fixture-code")
            waitFor(service, second.loginId, "AUTHENTICATED")
            assertEquals(profile.id, service.list().single().id)
            assertEquals("legacy-account", service.list().single().sourceAccountId)
            assertEquals(2, directories.size)
            assertEquals(directories.first(), directories.last())
        }
    }

    @Test
    fun cancellationStopsLoginAndDoesNotCreateAnAuthorizedAccount() = runBlocking {
        AntigravityAgyLoginService(config()).use { service ->
            val login = service.start("admin", "cancel fixture", null)
            waitFor(service, login.loginId, "AWAITING_CODE")
            service.stop(login.loginId, "admin")
            assertEquals("CANCELLED", service.get(login.loginId, "admin").state)
            assertTrue(service.list().isEmpty())
        }
    }

    @Test
    fun closedCliInputStopsTheFailedLoginProcess() = runBlocking {
        val processId = root.resolve("closed-input.pid")
        val script = """
            import json, os, pathlib, time
            pathlib.Path(PID_FILE).write_text(str(os.getpid()))
            os.close(0)
            print(json.dumps({"event":"authorize", "authorizeUrl":AUTH_URL}), flush=True)
            time.sleep(300)
        """.trimIndent().replace("PID_FILE", Json.encodeToString(processId.toString()))
            .replace("AUTH_URL", Json.encodeToString(url))
        AntigravityAgyLoginService(config(script)).use { service ->
            val login = service.start("admin", null, null)
            waitFor(service, login.loginId, "AWAITING_CODE")
            val process = ProcessHandle.of(Files.readString(processId).toLong()).orElseThrow()
            assertFailsWith<GatewayError> { service.submit(login.loginId, "admin", "fixture-code") }
            waitFor(service, login.loginId, "FAILED")
            withTimeout(8_000) { while (process.isAlive) delay(15) }
            assertTrue(service.list().isEmpty())
        }
    }

    @Test
    fun invalidCliOutputFailsWithoutExposingRawMessages() = runBlocking {
        AntigravityAgyLoginService(config("print('raw-secret-frame', flush=True)\n")).use { service ->
            val login = service.start("admin", null, null)
            waitFor(service, login.loginId, "FAILED")
            assertFalse("raw-secret" in Json.encodeToString(service.get(login.loginId, "admin")))
            assertTrue(service.list().isEmpty())
        }
    }

    private suspend fun waitFor(service: AntigravityAgyLoginService, id: String, state: String) {
        withTimeout(8_000) {
            while (service.get(id, "admin").state != state) delay(15)
        }
    }

    private fun config(body: String? = null): AntigravityAgyConfig {
        val script = root.resolve("login.py")
        Files.writeString(script, body ?: """
            import json, sys
            print(json.dumps({"event":"authorize", "authorizeUrl":AUTH_URL}), flush=True)
            assert json.loads(input())["code"] == "fixture-code"
            print(json.dumps({"event":"logged_in"}), flush=True)
        """.trimIndent().replace("AUTH_URL", Json.encodeToString(url)))
        val state = Files.createDirectory(root.resolve("accounts"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        return AntigravityAgyConfig(script, state)
    }
}
