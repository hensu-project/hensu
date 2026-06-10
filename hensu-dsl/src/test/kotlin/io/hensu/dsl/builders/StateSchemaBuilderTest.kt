package io.hensu.dsl.builders

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateSchemaBuilderTest {

    @Test
    fun `empty builder produces empty schema`() {
        val schema = StateSchemaBuilder().build()

        assertThat(schema.variables).isEmpty()
        assertThat(schema.contains("anything")).isFalse()
    }
}
