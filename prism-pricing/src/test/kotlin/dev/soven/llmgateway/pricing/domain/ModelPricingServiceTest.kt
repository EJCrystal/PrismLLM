package dev.soven.llmgateway.pricing.domain

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelPricingServiceTest {
    private val channelPrices = FakeChannelPrices()
    private val defaultPrices = FakeDefaultPrices()
    private val service = ModelPricingService(channelPrices, defaultPrices)

    @Test
    fun `channel override wins over model default`() = runBlocking {
        channelPrices.prices += channelPrice()
        defaultPrices.prices += defaultPrice()

        val resolved = service.resolve("ch", "upstream", "public")

        assertEquals(ModelPriceSource.CHANNEL_OVERRIDE, resolved?.source)
        assertEquals("price-ch-upstream", resolved?.channelPriceId)
        assertEquals("public", resolved?.modelId)
        assertEquals(BigDecimal("1"), resolved?.inputPricePerMillion)
    }

    @Test
    fun `channel override uses upstream model when public model id is blank`() = runBlocking {
        channelPrices.prices += channelPrice()

        val resolved = service.resolve("ch", "upstream", "  ")

        assertEquals(ModelPriceSource.CHANNEL_OVERRIDE, resolved?.source)
        assertEquals("upstream", resolved?.modelId)
    }

    @Test
    fun `falls back to the public model default`() = runBlocking {
        defaultPrices.prices += defaultPrice(modelId = "public", input = "9", output = "9")

        val resolved = service.resolve("ch", "upstream", "public")

        assertEquals(ModelPriceSource.MODEL_DEFAULT, resolved?.source)
        assertEquals("default-public", resolved?.defaultPriceId)
        assertEquals(BigDecimal("9"), resolved?.inputPricePerMillion)
    }

    @Test
    fun `tries the upstream model when the public model has no default`() = runBlocking {
        defaultPrices.prices += defaultPrice(modelId = "upstream", input = "3", output = "4")

        val resolved = service.resolve("ch", "upstream", "public")

        assertEquals(ModelPriceSource.MODEL_DEFAULT, resolved?.source)
        assertEquals("upstream", resolved?.modelId)
        assertEquals(BigDecimal("3"), resolved?.inputPricePerMillion)
    }

    @Test
    fun `skips the channel lookup when either channel id is missing`() = runBlocking {
        channelPrices.prices += channelPrice()
        defaultPrices.prices += defaultPrice()

        val withoutChannel = service.resolve(null, "upstream", "public")
        val withoutModel = service.resolve("ch", null, "public")

        assertEquals(ModelPriceSource.MODEL_DEFAULT, withoutChannel?.source)
        assertEquals(ModelPriceSource.MODEL_DEFAULT, withoutModel?.source)
        assertTrue(channelPrices.lookups.isEmpty())
    }

    @Test
    fun `returns null when no price matches`() = runBlocking {
        assertNull(service.resolve("ch", "upstream", "public"))
        assertNull(service.resolve(null, null, null))
        assertEquals(listOf("ch" to "upstream"), channelPrices.lookups)
        assertEquals(listOf("public", "upstream"), defaultPrices.lookups)
    }

    private class FakeChannelPrices : ModelPriceLookup {
        val prices = mutableListOf<ModelPriceRecord>()
        val lookups = mutableListOf<Pair<String, String>>()

        override suspend fun find(channelId: String, upstreamModel: String): ModelPriceRecord? {
            lookups += channelId to upstreamModel
            return prices.firstOrNull { it.channelId == channelId && it.upstreamModel == upstreamModel }
        }
    }

    private class FakeDefaultPrices : DefaultModelPriceLookup {
        val prices = mutableListOf<DefaultModelPriceRecord>()
        val lookups = mutableListOf<String>()

        override suspend fun find(modelId: String): DefaultModelPriceRecord? {
            lookups += modelId
            return prices.firstOrNull { it.modelId == modelId }
        }
    }

    private fun channelPrice(
        channelId: String = "ch",
        upstreamModel: String = "upstream",
        input: String = "1",
        output: String = "2",
    ) = ModelPriceRecord(
        id = "price-$channelId-$upstreamModel",
        channelId = channelId,
        channelName = channelId,
        upstreamModel = upstreamModel,
        currency = "USD",
        inputPricePerMillion = BigDecimal(input),
        outputPricePerMillion = BigDecimal(output),
        cachedInputPricePerMillion = null,
        createdAt = 1,
        updatedAt = 2,
    )

    private fun defaultPrice(
        modelId: String = "public",
        input: String = "9",
        output: String = "9",
    ) = DefaultModelPriceRecord(
        id = "default-$modelId",
        modelId = modelId,
        currency = "USD",
        inputPricePerMillion = BigDecimal(input),
        outputPricePerMillion = BigDecimal(output),
        cachedInputPricePerMillion = null,
        createdAt = 1,
        updatedAt = 2,
    )
}