package io.hensu.cli.commands;

import io.hensu.cli.visualizer.WorkflowVisualizer;
import io.hensu.core.workflow.Workflow;
import jakarta.inject.Inject;
import picocli.CommandLine;

@CommandLine.Command(name = "visualize", description = "Visualize workflow graph")
class WorkflowVisualizeCommand extends WorkflowCommand {

    @CommandLine.Parameters(
            index = "0",
            description = "Workflow name (from workflows/ directory)",
            arity = "0..1")
    String workflowName;

    @CommandLine.Option(
            names = "--format",
            defaultValue = "text",
            description = "Output format: text, mermaid")
    String format;

    @Inject WorkflowVisualizer visualizer;

    @Override
    protected void execute() {
        try {
            Workflow workflow = getWorkflow(workflowName);
            String output = visualizer.visualize(workflow, format);
            System.out.println(output);
        } catch (Exception e) {
            System.err.println(" [FAIL] Visualization failed: " + e.getMessage());
        }
    }
}
