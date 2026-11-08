package dev.soven.llmgateway.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IqVisionSettingsTest {
    private val defaults = IqVisionConfig(
        enabled = false,
        baseUrl = "http://127.0.0.1:8080/v1",
        model = "env-model",
        apiKey = "env-key",
        timeoutMs = 60_000,
    )

    @Test
    fun `missing settings keep environment defaults`() {
        val stored: IqVisionSettingsRecord? = null

        assertEquals(defaults, stored.effective(defaults))
    }

    @Test
    fun `stored settings win over environment`() {
        val stored = IqVisionSettingsRecord(
            enabled = true,
            baseUrl = "https://vision.example.com/v1",
            model = "vision-model",
            apiKey = "stored-key",
            timeoutMs = 90_000,
            updatedAt = 123,
        )

        val effective = stored.effective(defaults)

        assertTrue(effective.enabled)
        assertEquals("https://vision.example.com/v1", effective.baseUrl)
        assertEquals("vision-model", effective.model)
        assertEquals("stored-key", effective.apiKey)
        assertEquals(90_000, effective.timeoutMs)
    }

    @Test
    fun `blank endpoint and model fall back to deployment defaults`() {
        val stored = IqVisionSettingsRecord(
            enabled = true,
            baseUrl = "",
            model = "  ",
            apiKey = null,
            timeoutMs = 60_000,
            updatedAt = 123,
        )

        val effective = stored.effective(defaults)

        assertEquals("http://127.0.0.1:8080/v1", effective.baseUrl)
        assertEquals("env-model", effective.model)
        // 端点回退到环境变量后与环境变量端点一致，密钥也一并沿用。
        assertEquals("env-key", effective.apiKey)
    }

    @Test
    fun `clearing the api key falls back to the environment secret`() {
        val stored = IqVisionSettingsRecord(
            enabled = true,
            baseUrl = "http://127.0.0.1:8080/v1",
            model = "vision-model",
            apiKey = null,
            timeoutMs = 60_000,
            updatedAt = 123,
        )

        // 与管理台文案一致：「清除管理台保存的密钥，回退到环境变量」。
        assertEquals("env-key", stored.effective(defaults).apiKey)
    }

    @Test
    fun `config is usable only for http endpoints`() {
        assertTrue(defaults.usable)
        assertFalse(defaults.copy(baseUrl = "").usable)
        assertFalse(defaults.copy(baseUrl = "ftp://example.com").usable)
        assertTrue(defaults.copy(baseUrl = "https://example.com/v1").usable)
    }

    @Test
    fun `unreadable ciphertext surfaces without dropping the row`() {
        val stored = IqVisionSettingsRecord(
            enabled = true,
            baseUrl = "https://vision.example.com/v1",
            model = "vision-model",
            apiKey = null,
            timeoutMs = 60_000,
            updatedAt = 123,
            apiKeyUnreadable = true,
        )

        val effective = stored.effective(defaults)

        // 端点与模型仍然生效，管理台可以据此提示重填；密钥不能借用环境变量的。
        assertEquals("https://vision.example.com/v1", effective.baseUrl)
        assertEquals(null, effective.apiKey)
    }

    @Test
    fun `unreadable key does not fall back to the environment secret on the same endpoint`() {
        val stored = IqVisionSettingsRecord(
            enabled = true,
            baseUrl = "http://127.0.0.1:8080/v1",
            model = "vision-model",
            apiKey = null,
            timeoutMs = 60_000,
            updatedAt = 123,
            apiKeyUnreadable = true,
        )

        // 管理员显式保存过密钥，坏了就该提示重填；静默换环境变量凭据会在 401 时才暴露。
        assertEquals(null, stored.effective(defaults).apiKey)
    }

    @Test
    fun `environment api key is not sent to a different endpoint`() {
        val stored = IqVisionSettingsRecord(
            enabled = true,
            baseUrl = "https://vision.example.com/v1",
            model = "vision-model",
            apiKey = null,
            timeoutMs = 60_000,
            updatedAt = 123,
        )

        // 换了端点还回退环境变量密钥，就等于把部署凭据发给第三方服务。
        assertEquals(null, stored.effective(defaults).apiKey)
    }

    @Test
    fun `environment api key is kept when endpoint only differs by trailing slash or case`() {
        val stored = IqVisionSettingsRecord(
            enabled = true,
            baseUrl = "HTTP://127.0.0.1:8080/v1/",
            model = "vision-model",
            apiKey = null,
            timeoutMs = 60_000,
            updatedAt = 123,
        )

        assertEquals("env-key", stored.effective(defaults).apiKey)
    }
}
