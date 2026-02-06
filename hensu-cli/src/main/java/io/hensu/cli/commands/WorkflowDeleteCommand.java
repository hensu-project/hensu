package io.hensu.cli.commands;

import java.net.http.HttpResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/// Deletes a workflow definition from the Hensu server.
///
/// ### Usage
/// ```
/// hensu delete my-workflow
/// hensu delete my-workflow --server http://prod:8080
/// ```
@Command(name = "delete", description = "Delete a workflow from the server")
public class WorkflowDeleteCommand extends ServerCommand {

    @Parameters(index = "0", description = "Workflow ID")
    private String workflowId;

    @Override
    protected void execute() {
        HttpResponse<String> response = httpDelete("/api/v1/workflows/" + workflowId);

        if (response.statusCode() == 204) {
            System.out.println("Deleted: " + workflowId);
        } else {
            printHttpError(response.statusCode(), response.body());
        }
    }
}
