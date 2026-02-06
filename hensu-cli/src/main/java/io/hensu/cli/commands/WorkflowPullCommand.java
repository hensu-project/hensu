package io.hensu.cli.commands;

import java.net.http.HttpResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/// Pulls a workflow definition from the Hensu server.
///
/// ### Usage
/// ```
/// hensu pull my-workflow
/// hensu pull my-workflow --server http://prod:8080
/// ```
@Command(name = "pull", description = "Pull a workflow definition from the server")
public class WorkflowPullCommand extends ServerCommand {

    @Parameters(index = "0", description = "Workflow ID")
    private String workflowId;

    @Override
    protected void execute() {
        HttpResponse<String> response = httpGet("/api/v1/workflows/" + workflowId);

        if (response.statusCode() == 200) {
            System.out.println(response.body());
        } else {
            printHttpError(response.statusCode(), response.body());
        }
    }
}
