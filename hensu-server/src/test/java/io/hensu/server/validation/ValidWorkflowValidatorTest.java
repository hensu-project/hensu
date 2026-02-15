package io.hensu.server.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ValidWorkflowValidatorTest {

    private ValidWorkflowValidator validator;
    private ConstraintValidatorContext ctx;

    @BeforeEach
    void setUp() {
        validator = new ValidWorkflowValidator();
        ctx = mock(ConstraintValidatorContext.class);
        ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder =
                mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
        when(ctx.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);
        when(violationBuilder.addConstraintViolation()).thenReturn(ctx);
    }

    private Workflow minimalValidWorkflow() {
        return Workflow.builder()
                .id("order-processing")
                .version("1.0.0")
                .startNode("start")
                .nodes(
                        Map.of(
                                "start",
                                EndNode.builder().id("start").status(ExitStatus.SUCCESS).build()))
                .build();
    }

    private Workflow workflowWithAgent(String agentKey, AgentConfig agent) {
        return Workflow.builder()
                .id("wf-1")
                .version("1.0.0")
                .startNode("end")
                .nodes(
                        Map.of(
                                "end",
                                EndNode.builder().id("end").status(ExitStatus.SUCCESS).build()))
                .agents(Map.of(agentKey, agent))
                .build();
    }

    private Workflow workflowWithStandardNode(String nodeKey, StandardNode node) {
        return Workflow.builder()
                .id("wf-1")
                .version("1.0.0")
                .startNode(nodeKey)
                .agents(
                        Map.of(
                                "agent-1",
                                AgentConfig.builder()
                                        .id("agent-1")
                                        .role("assistant")
                                        .model("gpt-4")
                                        .build()))
                .nodes(
                        Map.of(
                                nodeKey,
                                node,
                                "end",
                                EndNode.builder().id("end").status(ExitStatus.SUCCESS).build()))
                .build();
    }

    @Test
    void shouldAcceptNullWorkflow() {
        assertThat(validator.isValid(null, ctx)).isTrue();
    }

    @Test
    void shouldAcceptValidMinimalWorkflow() {
        assertThat(validator.isValid(minimalValidWorkflow(), ctx)).isTrue();
    }

    @Nested
    class WorkflowIdValidation {

        @Test
        void shouldRejectIdWithNewline() {
            var wf =
                    Workflow.builder()
                            .id("wf\n-injected")
                            .version("1.0.0")
                            .startNode("start")
                            .nodes(
                                    Map.of(
                                            "start",
                                            EndNode.builder()
                                                    .id("start")
                                                    .status(ExitStatus.SUCCESS)
                                                    .build()))
                            .build();

            assertThat(validator.isValid(wf, ctx)).isFalse();
            verify(ctx).buildConstraintViolationWithTemplate(contains("id"));
        }

        @Test
        void shouldRejectIdWithSqlInjectionChars() {
            var wf =
                    Workflow.builder()
                            .id("wf'; DROP TABLE--")
                            .version("1.0.0")
                            .startNode("start")
                            .nodes(
                                    Map.of(
                                            "start",
                                            EndNode.builder()
                                                    .id("start")
                                                    .status(ExitStatus.SUCCESS)
                                                    .build()))
                            .build();

            assertThat(validator.isValid(wf, ctx)).isFalse();
        }
    }

    @Nested
    class VersionValidation {

        @Test
        void shouldAcceptCleanVersion() {
            assertThat(validator.isValid(minimalValidWorkflow(), ctx)).isTrue();
        }

        @Test
        void shouldRejectVersionWithNullByte() {
            var wf =
                    Workflow.builder()
                            .id("wf-1")
                            .version("1.0.0\u0000malicious")
                            .startNode("start")
                            .nodes(
                                    Map.of(
                                            "start",
                                            EndNode.builder()
                                                    .id("start")
                                                    .status(ExitStatus.SUCCESS)
                                                    .build()))
                            .build();

            assertThat(validator.isValid(wf, ctx)).isFalse();
        }

        @Test
        void shouldAcceptVersionWithNewline() {
            // Newlines are not in the DANGEROUS_CONTROL set — they're valid in free text
            var wf =
                    Workflow.builder()
                            .id("wf-1")
                            .version("1.0.0\n")
                            .startNode("start")
                            .nodes(
                                    Map.of(
                                            "start",
                                            EndNode.builder()
                                                    .id("start")
                                                    .status(ExitStatus.SUCCESS)
                                                    .build()))
                            .build();

            assertThat(validator.isValid(wf, ctx)).isTrue();
        }
    }

    @Nested
    class AgentValidation {

        @Test
        void shouldRejectAgentKeyWithSpecialChars() {
            var agent =
                    AgentConfig.builder().id("agent-1").role("assistant").model("gpt-4").build();

            var wf = workflowWithAgent("agent/../../etc", agent);

            assertThat(validator.isValid(wf, ctx)).isFalse();
        }

        @Test
        void shouldRejectAgentInstructionsWithNullByte() {
            var agent =
                    AgentConfig.builder()
                            .id("agent-1")
                            .role("assistant")
                            .model("gpt-4")
                            .instructions("Do the task\u0000hidden")
                            .build();

            var wf = workflowWithAgent("agent-1", agent);

            assertThat(validator.isValid(wf, ctx)).isFalse();
        }

        @Test
        void shouldAcceptAgentInstructionsWithNewlines() {
            var agent =
                    AgentConfig.builder()
                            .id("agent-1")
                            .role("assistant")
                            .model("gpt-4")
                            .instructions("Step 1: do X\nStep 2: do Y\nStep 3: review")
                            .build();

            var wf = workflowWithAgent("agent-1", agent);

            assertThat(validator.isValid(wf, ctx)).isTrue();
        }
    }

    @Nested
    class NodeValidation {

        @Test
        void shouldRejectNodeKeyWithNewline() {
            var wf =
                    Workflow.builder()
                            .id("wf-1")
                            .version("1.0.0")
                            .startNode("start\ninjected")
                            .nodes(
                                    Map.of(
                                            "start\ninjected",
                                            EndNode.builder()
                                                    .id("start\ninjected")
                                                    .status(ExitStatus.SUCCESS)
                                                    .build()))
                            .build();

            assertThat(validator.isValid(wf, ctx)).isFalse();
        }

        @Test
        void shouldRejectStandardNodeWithInvalidAgentId() {
            var node =
                    StandardNode.builder()
                            .id("step1")
                            .agentId("agent;DROP TABLE")
                            .prompt("Do the work")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build();

            var wf = workflowWithStandardNode("step1", node);

            assertThat(validator.isValid(wf, ctx)).isFalse();
        }

        @Test
        void shouldAcceptStandardNodeWithValidPromptContainingNewlines() {
            var node =
                    StandardNode.builder()
                            .id("step1")
                            .agentId("agent-1")
                            .prompt("Analyze the following:\n\n1. Item A\n2. Item B")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build();

            var wf = workflowWithStandardNode("step1", node);

            assertThat(validator.isValid(wf, ctx)).isTrue();
        }

        @Test
        void shouldRejectStandardNodeWithNullByteInPrompt() {
            var node =
                    StandardNode.builder()
                            .id("step1")
                            .agentId("agent-1")
                            .prompt("Normal prompt\u0000hidden instructions")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build();

            var wf = workflowWithStandardNode("step1", node);

            assertThat(validator.isValid(wf, ctx)).isFalse();
        }
    }

    @Nested
    class RubricValidation {

        @Test
        void shouldRejectRubricKeyWithSpecialChars() {
            var wf =
                    Workflow.builder()
                            .id("wf-1")
                            .version("1.0.0")
                            .startNode("start")
                            .nodes(
                                    Map.of(
                                            "start",
                                            EndNode.builder()
                                                    .id("start")
                                                    .status(ExitStatus.SUCCESS)
                                                    .build()))
                            .rubrics(Map.of("rubric<script>", "Evaluate quality"))
                            .build();

            assertThat(validator.isValid(wf, ctx)).isFalse();
        }

        @Test
        void shouldRejectRubricContentWithNullByte() {
            var wf =
                    Workflow.builder()
                            .id("wf-1")
                            .version("1.0.0")
                            .startNode("start")
                            .nodes(
                                    Map.of(
                                            "start",
                                            EndNode.builder()
                                                    .id("start")
                                                    .status(ExitStatus.SUCCESS)
                                                    .build()))
                            .rubrics(Map.of("quality", "Rate from 1-5\u0000hidden"))
                            .build();

            assertThat(validator.isValid(wf, ctx)).isFalse();
        }
    }

    @Nested
    class MultipleViolations {

        @Test
        void shouldReportAllViolationsNotJustFirst() {
            var wf =
                    Workflow.builder()
                            .id("bad id!!")
                            .version("1.0\u0000")
                            .startNode("also bad!!")
                            .nodes(
                                    Map.of(
                                            "also bad!!",
                                            EndNode.builder()
                                                    .id("also bad!!")
                                                    .status(ExitStatus.SUCCESS)
                                                    .build()))
                            .build();

            assertThat(validator.isValid(wf, ctx)).isFalse();

            // Should have reported multiple violations (id, version, startNode, node key, node id)
            verify(ctx, atLeast(3)).buildConstraintViolationWithTemplate(anyString());
        }
    }

    private static String contains(String substring) {
        return argThat(s -> s != null && s.contains(substring));
    }
}
