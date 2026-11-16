package dev.soven.prismllm.api

import dev.soven.prismllm.routing.readmodel.CredentialRouteType
import dev.soven.prismllm.shared.error.GatewayError
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Tag("unit")
class ModelCredentialRouteDtosTest {
    @Test
    fun `credential identity includes channel and type but not upstream model`() {
        val request = SaveModelCredentialRouteRequest(
            modelId = "vendor/family",
            expectedRevision = 0,
            entries = listOf(
                ModelCredentialRouteIdentityDto("channel-a", "static", "shared-id"),
                ModelCredentialRouteIdentityDto("channel-a", "oauth", "shared-id"),
                ModelCredentialRouteIdentityDto("channel-b", "static", "shared-id"),
            ),
        )
        assertEquals(3, request.identities().distinct().size)
        assertEquals(CredentialRouteType.OAUTH, request.identities()[1].credentialType)
        assertEquals("vendor/family", validateModelRouteModelId(request.modelId))
    }

    @Test
    fun `duplicate identities unknown credential types and invalid IDs are rejected`() {
        val entry = ModelCredentialRouteIdentityDto("channel", "static", "credential")
        val base = SaveModelCredentialRouteRequest("model", 0, listOf(entry))
        listOf(
            base.copy(entries = listOf(entry, entry)),
            base.copy(entries = listOf(entry.copy(credentialType = "unknown"))),
            base.copy(entries = listOf(entry.copy(channelId = " "))),
            base.copy(entries = listOf(entry.copy(credentialId = "x".repeat(37)))),
            base.copy(entries = listOf(entry.copy(credentialId = "credential\n"))),
            base.copy(modelId = " model"),
            base.copy(modelId = "x".repeat(201)),
            base.copy(expectedRevision = -1),
            base.copy(expectedRevision = Long.MAX_VALUE),
        ).forEach { invalid ->
            assertEquals("invalid_model_route", assertFailsWith<GatewayError> { invalid.identities() }.code)
        }
    }

    @Test
    fun `empty configured credential list is valid and writes have a specific audit action`() {
        assertEquals(emptyList(), SaveModelCredentialRouteRequest("model", 3, emptyList()).identities())
        assertEquals("model_route.update", auditAction("PUT", "/admin/api/model-routes"))
    }
}
