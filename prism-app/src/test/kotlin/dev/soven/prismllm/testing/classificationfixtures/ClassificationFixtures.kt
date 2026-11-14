package dev.soven.prismllm.testing.classificationfixtures

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

// Non-Surefire class names, excluded from the app-wide discovery, and disabled if explicitly selected for execution.
// The invalid classifications are discovery samples, not real application tests.
@Tag("component")
abstract class ClassifiedFixtureBase {
    @Test
    fun inherited() {
        error("Discovery must not execute inherited tests")
    }
}

@Disabled("Discovery-only fixture")
class ValidShapesFixture : ClassifiedFixtureBase() {
    init {
        error("Discovery must not construct test instances")
    }

    @Tag("component")
    @Tag("protocol")
    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun parameterized(value: Int) {
        error("Discovery must not invoke parameterized tests: $value")
    }

    @TestTemplate
    fun template() {
        error("Discovery must not invoke test templates")
    }

    @TestFactory
    fun factory(): List<DynamicTest> = error("Discovery must not invoke dynamic test factories")

    @Nested
    inner class NestedFixture {
        @Test
        fun nested() {
            error("Discovery must not execute nested tests")
        }
    }

    companion object {
        init {
            error("Discovery must not initialize test classes")
        }
    }
}

@Disabled("Discovery-only fixture")
@Tag("protocol")
class MissingFixture {
    @Test
    fun missing() {
        error("Discovery-only fixture")
    }
}

@Disabled("Discovery-only fixture")
@Tag("unit")
class ConflictingFixture {
    @Tag("browser")
    @Test
    fun methodConflict() {
        error("Discovery-only fixture")
    }

    @Tag("component")
    @Nested
    inner class NestedFixture {
        @Test
        fun nestedConflict() {
            error("Discovery-only fixture")
        }
    }
}

@Disabled("Discovery-only fixture")
class MissingTemplatesFixture {
    @ParameterizedTest
    @ValueSource(ints = [1])
    fun parameterized(value: Int) {
        error("Discovery-only fixture: $value")
    }

    @TestTemplate
    fun template() {
        error("Discovery-only fixture")
    }

    @TestFactory
    fun factory(): List<DynamicTest> = error("Discovery-only fixture")
}

@Disabled("Discovery-only fixture")
class EmptyFixture
