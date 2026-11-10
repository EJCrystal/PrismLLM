package dev.soven.prismllm.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CurrencyExchangeServiceTest {
    @Test
    fun `parses USD based rates for supported currencies`() {
        val client = HttpClient(Java)
        try {
            val service = CurrencyExchangeService(client)
            val rates = service.parseExchangeRates(
                """{"amount":1.0,"base":"USD","date":"2026-10-22","rates":{"CNY":6.6743,"EUR":0.89246}}""",
            )

            assertEquals("USD", rates.base)
            assertEquals("2026-10-22", rates.date)
            assertEquals("1", rates.rates["USD"])
            assertEquals("6.6743", rates.rates["CNY"])
            assertEquals("0.89246", rates.rates["EUR"])
        } finally {
            client.close()
        }
    }

    @Test
    fun `rejects incomplete exchange rate payload`() {
        val client = HttpClient(Java)
        try {
            val service = CurrencyExchangeService(client)
            assertFailsWith<IllegalArgumentException> {
                service.parseExchangeRates("""{"base":"USD","date":"2026-10-22","rates":{"EUR":0.89}}""")
            }
        } finally {
            client.close()
        }
    }
}
