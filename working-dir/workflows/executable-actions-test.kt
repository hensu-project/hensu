/**
 * Test workflow demonstrating executable actions.
 *
 * Actions:
 * - notify: Send a notification message
 * - execute: Run a command by ID (from commands.yaml)
 * - http: Make an HTTP call
 *
 * Security: Execute commands are NOT defined in the workflow.
 * They are referenced by ID and resolved from commands.yaml at runtime.
 */

fun actionsTestWorkflow() = workflow("executable-actions-test") {
    description = "Demonstrates node actions"
    version = "1.0.0"

    agents {
        agent("writer") {
            role = "A helpful assistant that generates short messages"
            model = "stub"
        }
    }

    graph {
        start at "generate"

        node("generate") {
            agent = "writer"
            prompt = """
                Generate a short message about the weather today.
                Keep it to one sentence.
            """.trimIndent()
            onSuccess goto "success"
        }

        action("success") {
            // Notification action
            notify("Workflow completed successfully! Output: {generate}")

            // Execute command by ID (from commands.yaml)
            execute("echo-result")

            // HTTP call (would fail without a real endpoint, but shows the DSL)
            // http("https://webhook.example.com/notify", "workflow-complete")

            onSuccess goto "exit"
        }

        end("exit")
    }
}
