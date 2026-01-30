package io.hensu.cli.visualizer;

import static io.hensu.cli.util.CliColors.*;

import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.*;
import io.hensu.core.workflow.transition.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/// ASCII text visualization format for workflows with ANSI colors.
@ApplicationScoped
public class TextVisualizationFormat implements VisualizationFormat {

    @Override
    public String getName() {
        return "text";
    }

    @Override
    public String render(Workflow workflow) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        "%sWorkflow:%s %s%n", BOLD, NC, accent(workflow.getMetadata().getName())));
        sb.append(gray("─".repeat(50))).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        Set<String> visited = new HashSet<>();
        Deque<NodeLevel> queue = new ArrayDeque<>();
        queue.add(new NodeLevel(workflow.getStartNode(), 0));

        while (!queue.isEmpty()) {
            NodeLevel current = queue.removeFirst();
            String nodeId = current.nodeId;
            int level = current.level;

            if (visited.contains(nodeId)) continue;
            visited.add(nodeId);

            String indent = "  ".repeat(level);
            Node node = workflow.getNodes().get(nodeId);
            if (node == null) continue;

            String nodeTypeColor = getNodeTypeColor(node.getNodeType());
            sb.append(
                    String.format(
                            "%s%s%s%s %s %s(%s)%s%n",
                            indent,
                            GRAY,
                            boxTop(),
                            NC,
                            bold(nodeId),
                            nodeTypeColor,
                            node.getNodeType(),
                            NC));

            switch (node) {
                case StandardNode standardNode -> {
                    if (standardNode.getAgentId() != null) {
                        sb.append(
                                String.format(
                                        "%s%s%s%s  Agent: %s%n",
                                        indent,
                                        GRAY,
                                        boxMid(),
                                        NC,
                                        accent(standardNode.getAgentId())));
                    }
                    if (standardNode.getRubricId() != null) {
                        sb.append(
                                String.format(
                                        "%s%s%s%s  Rubric: %s%n",
                                        indent, GRAY, boxMid(), NC, standardNode.getRubricId()));
                    }
                    if (standardNode.getReviewConfig() != null) {
                        sb.append(
                                String.format(
                                        "%s%s%s%s  Review: %s%s%s%n",
                                        indent,
                                        GRAY,
                                        boxMid(),
                                        NC,
                                        YELLOW,
                                        standardNode.getReviewConfig().getMode(),
                                        NC));
                    }

                    sb.append(
                            String.format(
                                    "%s%s%s%s  %sTransitions:%s%n",
                                    indent, GRAY, boxMid(), NC, GRAY, NC));
                    for (TransitionRule rule : standardNode.getTransitionRules()) {
                        if (rule instanceof SuccessTransition success) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s    %s %s %s(on success)%s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(success.getTargetNode()),
                                            GREEN,
                                            NC));
                            queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                        } else if (rule instanceof FailureTransition failure) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s    %s %s %s(on failure, retry: %d)%s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(failure.getThenTargetNode()),
                                            RED,
                                            failure.getRetryCount(),
                                            NC));
                            queue.add(new NodeLevel(failure.getThenTargetNode(), level + 1));
                        } else if (rule instanceof ScoreTransition score) {
                            for (ScoreCondition cond : score.getConditions()) {
                                sb.append(
                                        String.format(
                                                "%s%s%s%s    %s %s %s(score %s %s)%s%n",
                                                indent,
                                                GRAY,
                                                boxMid(),
                                                NC,
                                                arrow(),
                                                bold(cond.getTargetNode()),
                                                BLUE,
                                                cond.getOperator(),
                                                cond.getValue(),
                                                NC));
                                queue.add(new NodeLevel(cond.getTargetNode(), level + 1));
                            }
                        }
                    }
                }
                case LoopNode loopNode -> {
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Max iterations: %s%d%s%n",
                                    indent,
                                    GRAY,
                                    boxMid(),
                                    NC,
                                    BLUE,
                                    loopNode.getMaxIterations(),
                                    NC));
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Condition: %s%n",
                                    indent, GRAY, boxMid(), NC, loopNode.getCondition()));
                    if (loopNode.getBreakRules() != null) {
                        for (BreakRule rule : loopNode.getBreakRules()) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s  Break %s %s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(rule.getTargetNode())));
                            queue.add(new NodeLevel(rule.getTargetNode(), level + 1));
                        }
                    }
                }
                case ParallelNode parallelNode -> {
                    parallelNode.getBranches();
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Branches: %s%d%s%n",
                                    indent,
                                    GRAY,
                                    boxMid(),
                                    NC,
                                    BLUE,
                                    parallelNode.getBranches().length,
                                    NC));
                    for (Branch branch : parallelNode.getBranches()) {
                        sb.append(
                                String.format(
                                        "%s%s%s%s    %s %s %s(%s)%s%n",
                                        indent,
                                        GRAY,
                                        boxMid(),
                                        NC,
                                        bullet(),
                                        branch.getId(),
                                        GRAY,
                                        branch.getAgentId(),
                                        NC));
                    }
                    if (parallelNode.getConsensusConfig() != null) {
                        sb.append(
                                String.format(
                                        "%s%s%s%s  Consensus: %s%s%s%n",
                                        indent,
                                        GRAY,
                                        boxMid(),
                                        NC,
                                        YELLOW,
                                        parallelNode.getConsensusConfig().getStrategy(),
                                        NC));
                    }
                }
                case ForkNode forkNode -> {
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Targets: %s%d%s%n",
                                    indent,
                                    GRAY,
                                    boxMid(),
                                    NC,
                                    BLUE,
                                    forkNode.getTargets().size(),
                                    NC));
                    for (String target : forkNode.getTargets()) {
                        sb.append(
                                String.format(
                                        "%s%s%s%s    %s %s%n",
                                        indent, GRAY, boxMid(), NC, bullet(), bold(target)));
                        queue.add(new NodeLevel(target, level + 1));
                    }
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Wait for all: %s%s%s%n",
                                    indent,
                                    GRAY,
                                    boxMid(),
                                    NC,
                                    forkNode.isWaitForAll() ? GREEN : YELLOW,
                                    forkNode.isWaitForAll(),
                                    NC));
                    for (TransitionRule rule : forkNode.getTransitionRules()) {
                        if (rule instanceof SuccessTransition success) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s    %s %s %s(on complete)%s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(success.getTargetNode()),
                                            GREEN,
                                            NC));
                            queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                        }
                    }
                }
                case JoinNode joinNode -> {
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Awaiting: %s%d%s fork(s)%n",
                                    indent,
                                    GRAY,
                                    boxMid(),
                                    NC,
                                    BLUE,
                                    joinNode.getAwaitTargets().size(),
                                    NC));
                    for (String target : joinNode.getAwaitTargets()) {
                        sb.append(
                                String.format(
                                        "%s%s%s%s    %s %s%n",
                                        indent, GRAY, boxMid(), NC, bullet(), accent(target)));
                    }
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Merge: %s%s%s%n",
                                    indent,
                                    GRAY,
                                    boxMid(),
                                    NC,
                                    YELLOW,
                                    joinNode.getMergeStrategy(),
                                    NC));
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Output: %s%n",
                                    indent, GRAY, boxMid(), NC, joinNode.getOutputField()));
                    sb.append(
                            String.format(
                                    "%s%s%s%s  %sTransitions:%s%n",
                                    indent, GRAY, boxMid(), NC, GRAY, NC));
                    for (TransitionRule rule : joinNode.getTransitionRules()) {
                        if (rule instanceof SuccessTransition success) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s    %s %s %s(on success)%s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(success.getTargetNode()),
                                            GREEN,
                                            NC));
                            queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                        } else if (rule instanceof FailureTransition failure) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s    %s %s %s(on failure, retry: %d)%s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(failure.getThenTargetNode()),
                                            RED,
                                            failure.getRetryCount(),
                                            NC));
                            queue.add(new NodeLevel(failure.getThenTargetNode(), level + 1));
                        }
                    }
                }
                case GenericNode genericNode -> {
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Executor: %s%n",
                                    indent,
                                    GRAY,
                                    boxMid(),
                                    NC,
                                    accent(genericNode.getExecutorType())));
                    if (!genericNode.getConfig().isEmpty()) {
                        sb.append(
                                String.format(
                                        "%s%s%s%s  Config: %s%d%s entries%n",
                                        indent,
                                        GRAY,
                                        boxMid(),
                                        NC,
                                        BLUE,
                                        genericNode.getConfig().size(),
                                        NC));
                    }
                    if (genericNode.getRubricId() != null) {
                        sb.append(
                                String.format(
                                        "%s%s%s%s  Rubric: %s%n",
                                        indent, GRAY, boxMid(), NC, genericNode.getRubricId()));
                    }
                    sb.append(
                            String.format(
                                    "%s%s%s%s  %sTransitions:%s%n",
                                    indent, GRAY, boxMid(), NC, GRAY, NC));
                    for (TransitionRule rule : genericNode.getTransitionRules()) {
                        if (rule instanceof SuccessTransition success) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s    %s %s %s(on success)%s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(success.getTargetNode()),
                                            GREEN,
                                            NC));
                            queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                        } else if (rule instanceof FailureTransition failure) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s    %s %s %s(on failure, retry: %d)%s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(failure.getThenTargetNode()),
                                            RED,
                                            failure.getRetryCount(),
                                            NC));
                            queue.add(new NodeLevel(failure.getThenTargetNode(), level + 1));
                        } else if (rule instanceof ScoreTransition score) {
                            for (ScoreCondition cond : score.getConditions()) {
                                sb.append(
                                        String.format(
                                                "%s%s%s%s    %s %s %s(score %s %s)%s%n",
                                                indent,
                                                GRAY,
                                                boxMid(),
                                                NC,
                                                arrow(),
                                                bold(cond.getTargetNode()),
                                                BLUE,
                                                cond.getOperator(),
                                                cond.getValue(),
                                                NC));
                                queue.add(new NodeLevel(cond.getTargetNode(), level + 1));
                            }
                        }
                    }
                }
                case ActionNode actionNode -> {
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Actions: %s%d%s%n",
                                    indent,
                                    GRAY,
                                    boxMid(),
                                    NC,
                                    BLUE,
                                    actionNode.getActions().size(),
                                    NC));
                    for (var action : actionNode.getActions()) {
                        String actionType = action.getClass().getSimpleName();
                        sb.append(
                                String.format(
                                        "%s%s%s%s    %s %s%n",
                                        indent, GRAY, boxMid(), NC, bullet(), accent(actionType)));
                    }
                    sb.append(
                            String.format(
                                    "%s%s%s%s  %sTransitions:%s%n",
                                    indent, GRAY, boxMid(), NC, GRAY, NC));
                    for (TransitionRule rule : actionNode.getTransitionRules()) {
                        if (rule instanceof SuccessTransition success) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s    %s %s %s(on success)%s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(success.getTargetNode()),
                                            GREEN,
                                            NC));
                            queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                        } else if (rule instanceof FailureTransition failure) {
                            sb.append(
                                    String.format(
                                            "%s%s%s%s    %s %s %s(on failure, retry: %d)%s%n",
                                            indent,
                                            GRAY,
                                            boxMid(),
                                            NC,
                                            arrow(),
                                            bold(failure.getThenTargetNode()),
                                            RED,
                                            failure.getRetryCount(),
                                            NC));
                            queue.add(new NodeLevel(failure.getThenTargetNode(), level + 1));
                        }
                    }
                }
                case EndNode endNode -> {
                    String termColor =
                            endNode.getExitStatus().toString().equals("SUCCESS") ? GREEN : RED;
                    sb.append(
                            String.format(
                                    "%s%s%s%s  Exit: %s%s%s%n",
                                    indent,
                                    GRAY,
                                    boxMid(),
                                    NC,
                                    termColor,
                                    endNode.getExitStatus(),
                                    NC));
                }
                default -> {}
            }

            sb.append(String.format("%s%s%s%s%n", indent, GRAY, boxBottom(), NC));
            sb.append(System.lineSeparator());
        }

        return sb.toString();
    }

    private String getNodeTypeColor(NodeType type) {
        return switch (type) {
            case STANDARD, GENERIC, PARALLEL, FORK, JOIN -> BLUE;
            case END, ACTION -> GREEN;
            case LOOP -> YELLOW;
            case SUB_WORKFLOW -> GRAY;
        };
    }

    private record NodeLevel(String nodeId, int level) {}
}
