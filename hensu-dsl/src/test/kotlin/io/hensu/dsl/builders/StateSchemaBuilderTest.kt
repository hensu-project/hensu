package io.hensu.dsl.builders

import io.hensu.core.workflow.state.VarType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StateSchemaBuilderTest {

    // — isInput flag ——————————————————————————————————————————————————————

    @Nested
    inner class InputFlagTest {

        @Test
        fun `input sets isInput true`() {
            val schema = StateSchemaBuilder().apply { input("topic", VarType.STRING) }.build()
            val decl = schema.variables.first()
            assertThat(decl.name()).isEqualTo("topic")
            assertThat(decl.isInput).isTrue()
        }

        @Test
        fun `variable sets isInput false`() {
            val schema = StateSchemaBuilder().apply { variable("article", VarType.STRING) }.build()
            val decl = schema.variables.first()
            assertThat(decl.name()).isEqualTo("article")
            assertThat(decl.isInput).isFalse()
        }
    }

    // — Schema queries ————————————————————————————————————————————————————

    @Nested
    inner class SchemaQueryTest {

        @Test
        fun `declarations accumulate in definition order`() {
            val schema =
                StateSchemaBuilder()
                    .apply {
                        input("topic", VarType.STRING)
                        variable("article", VarType.STRING)
                        variable("approved", VarType.BOOLEAN)
                    }
                    .build()

            assertThat(schema.variables).hasSize(3)
            assertThat(schema.variables.map { it.name() })
                .containsExactly("topic", "article", "approved")
        }

        @Test
        fun `contains returns true for declared variable and false for unknown`() {
            val schema = StateSchemaBuilder().apply { input("topic", VarType.STRING) }.build()

            assertThat(schema.contains("topic")).isTrue()
            assertThat(schema.contains("undeclared")).isFalse()
        }

        @Test
        fun `typeOf returns correct type for declared variable`() {
            val schema = StateSchemaBuilder().apply { variable("count", VarType.NUMBER) }.build()

            assertThat(schema.typeOf("count")).hasValue(VarType.NUMBER)
        }

        @Test
        fun `typeOf returns empty for undeclared variable`() {
            val schema = StateSchemaBuilder().apply { variable("count", VarType.NUMBER) }.build()

            assertThat(schema.typeOf("undeclared")).isEmpty()
        }

        @Test
        fun `empty builder produces empty schema`() {
            val schema = StateSchemaBuilder().build()

            assertThat(schema.variables).isEmpty()
            assertThat(schema.contains("anything")).isFalse()
        }
    }
}
