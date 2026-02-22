package io.hensu.serialization;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.execution.parallel.ConsensusConfig;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.PlanningConfig;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.DoubleRange;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.*;
import io.hensu.core.workflow.transition.AlwaysTransition;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.ScoreTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowSerializerTest {

    @Test
    void roundTrip_standardWorkflow() {
        Workflow original = buildStandardWorkflow();

        String json = WorkflowSerializer.toJson(original);
        Workflow restored = WorkflowSerializer.fromJson(json);

        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getVersion()).isEqualTo(original.getVersion());
        assertThat(restored.getStartNode()).isEqualTo(original.getStartNode());
        assertThat(restored.getAgents()).containsKey("writer");
        assertThat(restored.getAgents().get("writer").getModel()).isEqualTo("claude-sonnet-4");
        assertThat(restored.getNodes()).hasSize(original.getNodes().size());
    }

    @Test
    void roundTrip_standardNode() {
        Workflow workflow = buildWorkflowWith("start", standardNode());

        String json = WorkflowSerializer.toJson(workflow);
        Workflow restored = WorkflowSerializer.fromJson(json);

        Node node = restored.getNodes().get("start");
        assertThat(node).isInstanceOf(StandardNode.class);
        StandardNode sn = (StandardNode) node;
        assertThat(sn.getAgentId()).isEqualTo("writer");
        assertThat(sn.getPrompt()).isEqualTo("Write something");
        assertThat(sn.getRubricId()).isEqualTo("quality");
        assertThat(sn.getTransitionRules()).hasSize(2);
    }

    @Test
    void roundTrip_endNode() {
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();
        StandardNode start =
                StandardNode.builder()
                        .id("start")
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("start")
                        .nodes(Map.of("start", start, "done", end))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        Node node = restored.getNodes().get("done");
        assertThat(node).isInstanceOf(EndNode.class);
        assertThat(((EndNode) node).getStatus()).isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void roundTrip_actionNode() {
        ActionNode actionNode =
                ActionNode.builder()
                        .id("notify")
                        .actions(
                                List.of(
                                        new Action.Send("slack", Map.of("channel", "#general")),
                                        new Action.Execute("deploy-script")))
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .build();
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("notify")
                        .nodes(Map.of("notify", actionNode, "done", end))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        ActionNode restoredAction = (ActionNode) restored.getNodes().get("notify");
        assertThat(restoredAction.getActions()).hasSize(2);

        Action.Send send = (Action.Send) restoredAction.getActions().getFirst();
        assertThat(send.getHandlerId()).isEqualTo("slack");
        assertThat(send.getPayload()).containsEntry("channel", "#general");

        Action.Execute exec = (Action.Execute) restoredAction.getActions().get(1);
        assertThat(exec.getCommandId()).isEqualTo("deploy-script");
    }

    @Test
    void roundTrip_genericNode() {
        GenericNode generic =
                GenericNode.builder()
                        .id("validate")
                        .executorType("validator")
                        .config(Map.of("minLength", 10, "maxLength", 1000))
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .rubricId("validation-rubric")
                        .build();
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("validate")
                        .nodes(Map.of("validate", generic, "done", end))
                        .rubrics(Map.of("validation-rubric", "test-rubric-path"))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        GenericNode restoredGeneric = (GenericNode) restored.getNodes().get("validate");
        assertThat(restoredGeneric.getExecutorType()).isEqualTo("validator");
        assertThat(restoredGeneric.getConfig()).containsEntry("minLength", 10);
        assertThat(restoredGeneric.getRubricId()).isEqualTo("validation-rubric");
    }

    @Test
    void roundTrip_parallelNode() {
        ParallelNode parallel =
                ParallelNode.builder("parallel")
                        .branch(new Branch("b1", "writer", "prompt1", null))
                        .branch(new Branch("b2", "reviewer", "prompt2", "rubric1", 2.0))
                        .consensus(
                                new ConsensusConfig("judge", ConsensusStrategy.JUDGE_DECIDES, 0.8))
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .build();
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("parallel")
                        .nodes(Map.of("parallel", parallel, "done", end))
                        .rubrics(Map.of("rubric1", "test-rubric-path"))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        ParallelNode restoredParallel = (ParallelNode) restored.getNodes().get("parallel");
        assertThat(restoredParallel.getBranchesList()).hasSize(2);
        assertThat(restoredParallel.getBranchesList().get(0).getWeight()).isEqualTo(1.0);
        assertThat(restoredParallel.getBranchesList().get(1).getWeight()).isEqualTo(2.0);
        assertThat(restoredParallel.getConsensusConfig().strategy())
                .isEqualTo(ConsensusStrategy.JUDGE_DECIDES);
    }

    @Test
    void roundTrip_forkJoinNodes() {
        ForkNode fork =
                ForkNode.builder("fork")
                        .targets(List.of("process-a", "process-b"))
                        .waitForAll(true)
                        .transitionRules(List.of(new SuccessTransition("join")))
                        .build();
        JoinNode join =
                JoinNode.builder("join")
                        .awaitTargets(List.of("fork"))
                        .mergeStrategy(MergeStrategy.COLLECT_ALL)
                        .outputField("results")
                        .timeoutMs(30000)
                        .failOnAnyError(false)
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .build();
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("fork")
                        .nodes(Map.of("fork", fork, "join", join, "done", end))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        ForkNode restoredFork = (ForkNode) restored.getNodes().get("fork");
        assertThat(restoredFork.getTargets()).containsExactly("process-a", "process-b");
        assertThat(restoredFork.isWaitForAll()).isTrue();

        JoinNode restoredJoin = (JoinNode) restored.getNodes().get("join");
        assertThat(restoredJoin.getMergeStrategy()).isEqualTo(MergeStrategy.COLLECT_ALL);
        assertThat(restoredJoin.getOutputField()).isEqualTo("results");
        assertThat(restoredJoin.getTimeoutMs()).isEqualTo(30000);
        assertThat(restoredJoin.isFailOnAnyError()).isFalse();
    }

    @Test
    void roundTrip_subWorkflowNode() {
        SubWorkflowNode sub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("child-workflow")
                        .inputMapping(Map.of("input", "parentInput"))
                        .outputMapping(Map.of("childOutput", "parentOutput"))
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .build();
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("sub")
                        .nodes(Map.of("sub", sub, "done", end))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        SubWorkflowNode restoredSub = (SubWorkflowNode) restored.getNodes().get("sub");
        assertThat(restoredSub.getWorkflowId()).isEqualTo("child-workflow");
        assertThat(restoredSub.getInputMapping()).containsEntry("input", "parentInput");
        assertThat(restoredSub.getOutputMapping()).containsEntry("childOutput", "parentOutput");
    }

    @Test
    void roundTrip_scoreTransition() {
        StandardNode start =
                StandardNode.builder()
                        .id("start")
                        .transitionRules(
                                List.of(
                                        new ScoreTransition(
                                                List.of(
                                                        new ScoreCondition(
                                                                ComparisonOperator.GTE,
                                                                8.0,
                                                                null,
                                                                "high"),
                                                        new ScoreCondition(
                                                                ComparisonOperator.RANGE,
                                                                null,
                                                                new DoubleRange(4.0, 7.9),
                                                                "medium"),
                                                        new ScoreCondition(
                                                                ComparisonOperator.LT,
                                                                4.0,
                                                                null,
                                                                "low")))))
                        .build();
        EndNode high = EndNode.builder().id("high").status(ExitStatus.SUCCESS).build();
        EndNode medium = EndNode.builder().id("medium").status(ExitStatus.SUCCESS).build();
        EndNode low = EndNode.builder().id("low").status(ExitStatus.FAILURE).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("start")
                        .nodes(Map.of("start", start, "high", high, "medium", medium, "low", low))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        StandardNode restoredStart = (StandardNode) restored.getNodes().get("start");
        ScoreTransition st = (ScoreTransition) restoredStart.getTransitionRules().getFirst();
        assertThat(st.conditions()).hasSize(3);
        assertThat(st.conditions().get(1).range()).isEqualTo(new DoubleRange(4.0, 7.9));
    }

    @Test
    void roundTrip_alwaysTransition() {
        StandardNode start =
                StandardNode.builder()
                        .id("start")
                        .transitionRules(List.of(new AlwaysTransition()))
                        .build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("start")
                        .nodes(Map.of("start", start))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        StandardNode restoredStart = (StandardNode) restored.getNodes().get("start");
        assertThat(restoredStart.getTransitionRules().getFirst())
                .isInstanceOf(AlwaysTransition.class);
    }

    @Test
    void roundTrip_planningConfig() {
        StandardNode start =
                StandardNode.builder()
                        .id("start")
                        .agentId("planner")
                        .planningConfig(PlanningConfig.forStaticWithReview())
                        .staticPlan(
                                Plan.staticPlan(
                                        "start",
                                        List.of(
                                                PlannedStep.pending(
                                                        0, "search", Map.of(), "Search"))))
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .build();
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("start")
                        .nodes(Map.of("start", start, "done", end))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        StandardNode sn = (StandardNode) restored.getNodes().get("start");
        assertThat(sn.getPlanningConfig().isStatic()).isTrue();
        assertThat(sn.getPlanningConfig().reviewBeforeExecute()).isTrue();
        assertThat(sn.getStaticPlan().steps()).hasSize(1);
        assertThat(sn.getStaticPlan().steps().getFirst().toolName()).isEqualTo("search");
    }

    @Test
    void roundTrip_reviewConfig() {
        StandardNode start =
                StandardNode.builder()
                        .id("start")
                        .reviewConfig(new ReviewConfig(ReviewMode.REQUIRED, true, false))
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .build();
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("start")
                        .nodes(Map.of("start", start, "done", end))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        StandardNode sn = (StandardNode) restored.getNodes().get("start");
        assertThat(sn.getReviewConfig().mode()).isEqualTo(ReviewMode.REQUIRED);
        assertThat(sn.getReviewConfig().allowBacktrack()).isTrue();
        assertThat(sn.getReviewConfig().allowEdit()).isFalse();
    }

    @Test
    void roundTrip_agentConfig_maintainContext() {
        AgentConfig agent =
                AgentConfig.builder()
                        .id("agent1")
                        .role("writer")
                        .model("claude-sonnet-4")
                        .temperature(0.5)
                        .maxTokens(4096)
                        .tools(List.of("search", "calculator"))
                        .maintainContext(true)
                        .instructions("Be concise")
                        .build();

        StandardNode start =
                StandardNode.builder()
                        .id("start")
                        .agentId("agent1")
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .build();
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("start")
                        .agents(Map.of("agent1", agent))
                        .nodes(Map.of("start", start, "done", end))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        AgentConfig restoredAgent = restored.getAgents().get("agent1");
        assertThat(restoredAgent.isMaintainContext()).isTrue();
        assertThat(restoredAgent.getMaxTokens()).isEqualTo(4096);
        assertThat(restoredAgent.getTools()).containsExactly("search", "calculator");
        assertThat(restoredAgent.getInstructions()).isEqualTo("Be concise");
    }

    @Test
    void roundTrip_loopNode() {
        LoopNode loop = new LoopNode("loop");
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Workflow workflow =
                Workflow.builder()
                        .id("test")
                        .startNode("loop")
                        .nodes(Map.of("loop", loop, "done", end))
                        .build();

        Workflow restored = WorkflowSerializer.fromJson(WorkflowSerializer.toJson(workflow));

        assertThat(restored.getNodes().get("loop")).isInstanceOf(LoopNode.class);
    }

    // --- helpers ---

    private Workflow buildStandardWorkflow() {
        return Workflow.builder()
                .id("my-workflow")
                .version("2.0.0")
                .startNode("start")
                .agents(
                        Map.of(
                                "writer",
                                AgentConfig.builder()
                                        .id("writer")
                                        .role("writer")
                                        .model("claude-sonnet-4")
                                        .build()))
                .rubrics(Map.of("quality", "rubric-001"))
                .nodes(
                        Map.of(
                                "start",
                                standardNode(),
                                "done",
                                EndNode.builder().id("done").status(ExitStatus.SUCCESS).build()))
                .build();
    }

    private StandardNode standardNode() {
        return StandardNode.builder()
                .id("start")
                .agentId("writer")
                .prompt("Write something")
                .rubricId("quality")
                .transitionRules(
                        List.of(new SuccessTransition("done"), new FailureTransition(3, "done")))
                .build();
    }

    private Workflow buildWorkflowWith(String nodeId, Node node) {
        EndNode end = EndNode.builder().id("done").status(ExitStatus.SUCCESS).build();

        Map<String, Node> nodes =
                node.getId().equals("done")
                        ? Map.of(nodeId, node)
                        : Map.of(nodeId, node, "done", end);

        var builder = Workflow.builder().id("test").startNode(nodeId).nodes(nodes);

        if (node.getRubricId() != null && !node.getRubricId().isEmpty()) {
            builder.rubrics(Map.of(node.getRubricId(), "test-rubric-path"));
        }

        return builder.build();
    }
}
