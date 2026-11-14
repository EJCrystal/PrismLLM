package dev.soven.prismllm.testsupport.discoveryfixtures

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

// Not Surefire test names. Explicitly excluded by the module-root test and disabled for accidental execution.
@Disabled("Discovery-only probe")
@Tag("channel")
class MixedClassificationProbe {
    init {
        error("Discovery must not construct probes")
    }

    @Tag("component")
    @Tag("protocol")
    @Test
    fun classified() {
        error("Discovery must not execute probes")
    }

    @Tag("shard-2")
    @Test
    fun unclassified() {
        error("Discovery must not execute probes")
    }

    @Tag("browser")
    @Tag("unit")
    @Test
    fun conflicting() {
        error("Discovery must not execute probes")
    }
}

class EmptyDiscoveryProbe
