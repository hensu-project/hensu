/**
 * Example: Generic nodes for custom execution logic
 *
 * This workflow demonstrates:
 * - Standard node output being validated by generic nodes
 * - Multiple generic nodes sharing the same executor ("validator")
 * - Generic nodes with unique executors ("data-transformer", "api-caller")
 * - Output chaining: node output → validation → transformation → API call → summary
 *
 * Handlers are CDI beans in hensu-cli: ValidatorHandler, DataTransformerHandler, ApiCallerHandler
 */
fun workflow() = workflow("generic-node-demo") {
    description = "Demonstrates GenericNode for custom logic"

    agents {
        agent("writer") {
            model = "stub"
            role = "Content writer"
        }
    }

    graph {
        start at "generate-initial-content"

        // Standard node: Generate initial content to be processed
        // Output stored in context as "generate-initial-content"
        node("generate-initial-content") {
            agent = "writer"
            prompt = "Generate a short text about workflow automation benefits."
            onSuccess goto "validate-content"
        }

        // Generic node 1: Content validation (uses "validator" handler)
        // References the output from previous node by its ID
        generic("validate-content") {
            executorType = "validator"
            config {
                "field" to "generate-initial-content"
                "minLength" to 10
                "maxLength" to 5000
                "required" to true
            }
            onSuccess goto "validate-format"
            onFailure retry 0 otherwise "validation-failed"
        }

        // Generic node 2: Format validation (reuses "validator" handler)
        // Same executor type, different config
        generic("validate-format") {
            executorType = "validator"
            config {
                "field" to "generate-initial-content"
                "pattern" to "^[\\s\\S]+$"  // Any non-empty content
                "errorMessage" to "Content format is invalid"
            }
            onSuccess goto "transform-data"
            onFailure retry 0 otherwise "validation-failed"
        }

        // Generic node 3: Data transformation (unique "data-transformer" handler)
        // Input: generate-initial-content, Output: transformed_data
        generic("transform-data") {
            executorType = "data-transformer"
            config {
                "inputField" to "generate-initial-content"
                "outputField" to "transformed_data"
                "operations" to listOf("trim", "normalize")
            }
            onSuccess goto "call-external-api"
            onFailure retry 2 otherwise "transform-failed"
        }

        // Generic node 4: External API call (unique "api-caller" handler)
        // Uses output from transform-data, outputs: api_response
        generic("call-external-api") {
            executorType = "api-caller"
            config {
                "endpoint" to "https://api.example.com/process"
                "method" to "POST"
                "inputField" to "transformed_data"
                "outputField" to "api_response"
                "timeout" to 30000
            }
            onSuccess goto "generate-summary"
            onFailure retry 3 otherwise "api-failed"
        }

        // Standard node: Uses outputs from generic nodes
        // {transformed_data} and {api_response} are available in prompt
        node("generate-summary") {
            agent = "writer"
            prompt = """
                Based on the processed data and API response, generate a summary.

                Processed Content: {transformed_data}
                API Response: {api_response}

                Create a well-formatted summary of the results.
            """.trimIndent()
            onSuccess goto "success"
        }

        // End nodes
        end("validation-failed", ExitStatus.FAILURE)
        end("transform-failed", ExitStatus.FAILURE)
        end("api-failed", ExitStatus.FAILURE)
        end("success")
    }
}