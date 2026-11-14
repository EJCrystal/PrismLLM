package dev.soven.prismllm.testsupport.moduleroot

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** A sibling package with a non-Surefire class name that module-root discovery must still find. */
@Disabled("Discovery-only module-root probe")
@Tag("component")
class ModuleRootProbe {
    init {
        error("Discovery must not construct the module-root probe")
    }

    @Test
    fun discoveredFromSiblingPackage() {
        error("Discovery must not execute the module-root probe")
    }
}
