package io.hensu.core.plan;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// Planner that returns predefined plans from DSL.
///
/// Static plans are defined at compile time in the workflow DSL and cannot
/// be revised at runtime. This planner resolves `{variable}` placeholders
/// in step arguments using the workflow context.
///
/// ### Template Resolution
/// Supports `{variable}` syntax in step arguments:
/// - `{orderId}` → resolved from context map
/// - Nested access not supported (use flattened keys)
/// - Unresolved variables are left as-is with warning logged
///
/// ### Usage
/// {@snippet :
/// Plan predefinedPlan = Plan.staticPlan("node", List.of(
///     PlannedStep.pending(0, "get_order", Map.of("id", "{orderId}"), "Fetch order")
/// ));
///
/// StaticPlanner planner = new StaticPlanner(predefinedPlan);
/// Plan resolved = planner.createPlan(new PlanRequest(
///     "Process order",
///     List.of(),
///     Map.of("orderId", "12345"),
///     PlanConstraints.forStaticPlan()
/// ));
/// // resolved.steps().get(0).arguments() → Map.of("id", "12345")
/// }
///
/// @see Planner for the interface contract
/// @see Plan for plan structure
public class StaticPlanner implements Planner {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^}]+)}");

    private final Plan predefinedPlan;

    /// Creates a static planner with a predefined plan.
    ///
    /// @param predefinedPlan the plan to return (with placeholder resolution), not null
    /// @throws NullPointerException if predefinedPlan is null
    public StaticPlanner(Plan predefinedPlan) {
        this.predefinedPlan =
                Objects.requireNonNull(predefinedPlan, "predefinedPlan must not be null");
    }

    @Override
    public Plan createPlan(PlanRequest request) {
        List<PlannedStep> resolvedSteps =
                predefinedPlan.steps().stream()
                        .map(step -> resolveStep(step, request.context()))
                        .toList();

        return predefinedPlan.withSteps(resolvedSteps);
    }

    @Override
    public Plan revisePlan(Plan currentPlan, RevisionContext context) throws PlanRevisionException {
        throw new PlanRevisionException("Static plans cannot be revised");
    }

    /// Resolves placeholders in a step's action arguments and description.
    private PlannedStep resolveStep(PlannedStep step, Map<String, Object> context) {
        PlanStepAction resolvedAction =
                switch (step.action()) {
                    case PlanStepAction.ToolCall tc -> {
                        Map<String, Object> resolvedArgs =
                                tc.arguments().entrySet().stream()
                                        .collect(
                                                Collectors.toMap(
                                                        Map.Entry::getKey,
                                                        e -> resolveValue(e.getValue(), context)));
                        yield new PlanStepAction.ToolCall(tc.toolName(), resolvedArgs);
                    }
                    case PlanStepAction.Synthesize s ->
                            new PlanStepAction.Synthesize(
                                    s.agentId(), resolvePlaceholders(s.prompt(), context));
                };

        return new PlannedStep(
                step.index(),
                resolvedAction,
                resolvePlaceholders(step.description(), context),
                step.status());
    }

    /// Resolves placeholders in a value (recursively for nested structures).
    private Object resolveValue(Object value, Map<String, Object> context) {
        if (value instanceof String str) {
            return resolvePlaceholders(str, context);
        } else if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .collect(
                            Collectors.toMap(
                                    e -> e.getKey().toString(),
                                    e -> resolveValue(e.getValue(), context)));
        } else if (value instanceof List<?> list) {
            return list.stream().map(item -> resolveValue(item, context)).toList();
        }
        return value;
    }

    /// Replaces `{variable}` placeholders with values from context.
    private String resolvePlaceholders(String template, Map<String, Object> context) {
        if (template == null || !template.contains("{")) {
            return template;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String variableName = matcher.group(1);
            Object value = context.get(variableName);

            String replacement = value != null ? value.toString() : matcher.group(0);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }
}
