package io.hensu.dsl.runners

import io.hensu.core.HensuFactory
import io.hensu.core.agent.AgentResponse
import io.hensu.core.execution.ExecutionListener
import io.hensu.core.execution.result.ExecutionResult
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.parsers.KotlinScriptParser
import java.nio.file.Path

/**
 * Standalone CLI runner for executing Hensu workflows.
 *
 * Provides a simple command-line interface for parsing and executing workflow scripts without
 * requiring the full CLI infrastructure.
 *
 * Usage:
 * ```
 * java -cp <classpath> io.hensu.dsl.runners.SimpleRunnerKt <working-dir> <workflow-name> [options]
 * ```
 *
 * Options:
 * - `--verbose`, `-v`: Show agent inputs and outputs during execution
 * - `--stub`, `-s`: Use stub agents (no API calls, for testing)
 *
 * Example:
 * ```
 * java -cp hensu.jar io.hensu.dsl.runners.SimpleRunnerKt working-dir georgia-discovery --verbose
 * ```
 *
 * @see KotlinScriptParser for workflow parsing
 * @see HensuFactory for environment creation
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: SimpleRunner <working-dir> <workflow-name> [--verbose] [--stub]")
        println("Example: SimpleRunner working-dir georgia-discovery --verbose --stub")
        println()
        println("Arguments:")
        println("  <working-dir>   Directory containing workflows/, prompts/, rubrics/")
        println("  <workflow-name> Name of the workflow (with or without .kt extension)")
        println()
        println("Options:")
        println("  --verbose, -v  Show agent inputs and outputs")
        println("  --stub, -s     Use stub agents (no API calls)")
        return
    }

    val workingDirPath = Path.of(args[0])
    val workflowName = args[1]
    val verbose = args.any { it == "--verbose" || it == "-v" }
    val stubMode = args.any { it == "--stub" || it == "-s" }

    val workingDir = WorkingDirectory.of(workingDirPath)
    println("Working directory: ${workingDir.root()}")
    println("Parsing workflow: $workflowName")

    val parser = KotlinScriptParser()
    val workflow = parser.parse(workingDir, workflowName)

    println()
    println(" [OK] Workflow loaded: ${workflow.metadata.name}")
    println("   Description: ${workflow.metadata.description}")
    println("   Agents: ${workflow.agents.size}")
    println("   Nodes: ${workflow.nodes.size}")
    println()

    // Create Hensu environment with credentials from environment variables
    val environment =
        HensuFactory.builder().loadCredentialsFromEnvironment().stubMode(stubMode).build()
    val executor = environment.workflowExecutor

    println("   Starting workflow execution...")
    if (verbose) {
        println("   (verbose mode enabled)")
    }
    println()

    try {
        val listener = if (verbose) createVerboseListener() else ExecutionListener.NOOP
        val result = executor.execute(workflow, emptyMap<String, Any>(), listener)

        when (result) {
            is ExecutionResult.Completed -> {
                println()
                println(" [OK] Workflow completed successfully!")
                println("   Exit status: ${result.exitStatus}")
                println("   Total steps: ${result.finalState.history.steps.size}")
                println("   Backtracks: ${result.finalState.history.backtracks.size}")

                if (result.finalState.history.backtracks.isNotEmpty()) {
                    println()
                    println("   Backtrack Summary:")
                    result.finalState.history.backtracks.forEach { bt ->
                        println("   ${bt.from} -> ${bt.to} (${bt.type}): ${bt.reason}")
                    }
                }
            }
            is ExecutionResult.Success -> {
                println()
                println(" [OK] Workflow completed!")
                println("   Total steps: ${result.currentState.history.steps.size}")
            }
            is ExecutionResult.Rejected -> {
                println()
                println(" [FAIL] Workflow rejected!")
                println("   Reason: ${result.reason}")
            }
            is ExecutionResult.Failure -> {
                println()
                println(" [FAIL] Workflow failed!")
                println("   Error: ${result.e.message}")
            }
            else -> return
        }
    } catch (e: Exception) {
        System.err.println(" [FAIL] Workflow execution failed: ${e.message}")
        e.printStackTrace()
    } finally {
        environment.close()
    }
}

/**
 * Creates an execution listener that prints agent inputs and outputs to stdout.
 *
 * Used in verbose mode to provide visibility into workflow execution. Formats output with clear
 * delimiters showing node ID, agent ID, and content.
 *
 * @return listener that prints to stdout, never null
 */
private fun createVerboseListener(): ExecutionListener {
    return object : ExecutionListener {
        override fun onAgentStart(nodeId: String, agentId: String, prompt: String) {
            println("+-------------------------------------------------------------")
            println("| * INPUT [$nodeId] -> Agent: $agentId")
            println("+-------------------------------------------------------------")
            printIndented(prompt)
            println("+-------------------------------------------------------------")
            println()
        }

        override fun onAgentComplete(nodeId: String, agentId: String, response: AgentResponse) {
            val (status, output) =
                when (response) {
                    is AgentResponse.TextResponse -> "OK" to response.content()
                    is AgentResponse.ToolRequest ->
                        "TOOL" to "Tool: ${response.toolName()} - ${response.reasoning()}"
                    is AgentResponse.PlanProposal ->
                        "PLAN" to
                            "Plan with ${response.steps().size} steps - ${response.reasoning()}"
                    is AgentResponse.Error -> "FAIL" to response.message()
                }
            println("+-------------------------------------------------------------")
            println("| * OUTPUT [$nodeId] <- Agent: $agentId ($status)")
            println("+-------------------------------------------------------------")
            printIndented(output)
            println("+-------------------------------------------------------------")
            println()
        }

        private fun printIndented(text: String?) {
            if (text.isNullOrEmpty()) {
                println("| (empty)")
                return
            }
            text.split("\n").forEach { line -> println("| $line") }
        }
    }
}
