package io.hensu.cli.commands;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;

/// Lists all workflows on the Hensu server for the current tenant.
///
/// ### Usage
/// ```
/// hensu list
/// hensu list --server http://prod:8080 --tenant acme
/// ```
@Command(name = "list", description = "List workflows on the server")
public class WorkflowListCommand extends ServerCommand {

    @Override
    protected void execute() {
        HttpResponse<String> response = httpGet("/api/v1/workflows");

        if (response.statusCode() != 200) {
            printHttpError(response.statusCode(), response.body());
            return;
        }

        try {
            List<Map<String, String>> workflows =
                    new ObjectMapper().readValue(response.body(), new TypeReference<>() {});

            if (workflows.isEmpty()) {
                System.out.println("No workflows found.");
                return;
            }

            System.out.printf("%-30s %s%n", "ID", "VERSION");
            System.out.println("-".repeat(45));
            for (Map<String, String> w : workflows) {
                System.out.printf(
                        "%-30s %s%n", w.getOrDefault("id", "?"), w.getOrDefault("version", "?"));
            }
        } catch (Exception e) {
            System.err.println("Failed to parse response: " + e.getMessage());
        }
    }
}
