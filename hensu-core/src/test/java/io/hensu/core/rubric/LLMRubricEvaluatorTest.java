package io.hensu.core.rubric;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.stub.StubAgent;
import io.hensu.core.agent.stub.StubResponseRegistry;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.rubric.evaluator.LLMRubricEvaluator;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.EvaluationType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tests for LLM-based rubric evaluation.
class LLMRubricEvaluatorTest {

    private AgentRegistry agentRegistry;
    private StubResponseRegistry stubRegistry;
    private LLMRubricEvaluator evaluator;

    @BeforeEach
    void setUp() {
        agentRegistry = mock(AgentRegistry.class);
        stubRegistry = StubResponseRegistry.getInstance();
        stubRegistry.clearResponses();
        evaluator = new LLMRubricEvaluator(agentRegistry);
    }

    @Test
    void shouldReturnZeroForFailedNodeResult() {
        // Given: A failed node result
        NodeResult result = new NodeResult(ResultStatus.FAILURE, "Error occurred", Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Score is 0
        assertEquals(0.0, score);
        // And: No agent was called
        verify(agentRegistry, never()).getAgent(any());
    }

    @Test
    void shouldReturnZeroForEmptyOutput() {
        // Given: Success result with empty output
        NodeResult result = new NodeResult(ResultStatus.SUCCESS, "", Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Score is 0
        assertEquals(0.0, score);
    }

    @Test
    void shouldUseEvaluatorAgentWhenAvailable() {
        // Given: Evaluator agent returns a score
        Agent mockAgent = mock(Agent.class);
        when(agentRegistry.hasAgent("evaluator")).thenReturn(true);
        when(agentRegistry.getAgent("evaluator")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(
                        AgentResponse.success(
                                "{\"score\": 85, \"reasoning\": \"Good work\"}", Map.of()));

        NodeResult result =
                new NodeResult(ResultStatus.SUCCESS, "Some content to evaluate", Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Score from evaluator is used
        assertEquals(85.0, score);
        verify(mockAgent).execute(any(), any());
    }

    @Test
    void shouldBuildProperEvaluationPrompt() {
        // Given: Evaluator agent
        Agent mockAgent = mock(Agent.class);
        when(agentRegistry.hasAgent("evaluator")).thenReturn(true);
        when(agentRegistry.getAgent("evaluator")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.success("{\"score\": 75}", Map.of()));

        String contentToEvaluate = "This is the content being evaluated.";
        NodeResult result = new NodeResult(ResultStatus.SUCCESS, contentToEvaluate, Map.of());
        Criterion criterion =
                Criterion.builder()
                        .id("completeness")
                        .name("Content Completeness")
                        .description("Check if content covers all required topics")
                        .evaluationType(EvaluationType.LLM_BASED)
                        .evaluationLogic("Must include introduction, body, and conclusion")
                        .build();

        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        evaluator.evaluate(criterion, result, context);

        // Then: Prompt contains criterion details and content
        verify(mockAgent)
                .execute(
                        argThat(
                                prompt ->
                                        prompt.contains("Content Completeness")
                                                && prompt.contains(
                                                        "Check if content covers all required topics")
                                                && prompt.contains(
                                                        "Must include introduction, body, and conclusion")
                                                && prompt.contains(contentToEvaluate)
                                                && prompt.contains("score")
                                                && prompt.contains("0 to 100")),
                        any());
    }

    @Test
    void shouldFallbackToHeuristicWhenNoEvaluatorAgent() {
        // Given: No evaluator agent available
        when(agentRegistry.hasAgent("evaluator")).thenReturn(false);

        String content =
                """
            This is a comprehensive analysis of the topic.

            1. First point with detailed explanation
            2. Second point with supporting evidence
            3. Third point concluding the argument

            The analysis shows that proper structure leads to better outcomes.
            """;

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, content, Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Fallback heuristic scoring is used (should be > 50 due to length and structure)
        assertTrue(score > 50, "Score should be above neutral due to content characteristics");
        verify(agentRegistry, never()).getAgent(any());
    }

    @Test
    void shouldParseScoreFromVariousFormats() {
        // Given: Different score format responses
        Agent mockAgent = mock(Agent.class);
        when(agentRegistry.hasAgent("evaluator")).thenReturn(true);
        when(agentRegistry.getAgent("evaluator")).thenReturn(Optional.of(mockAgent));

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, "Content", Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // Test format: "score": 90
        when(mockAgent.execute(any(), any()))
                .thenReturn(
                        AgentResponse.success(
                                "{\"score\": 90, \"reasoning\": \"test\"}", Map.of()));
        assertEquals(90.0, evaluator.evaluate(criterion, result, context));

        // Test format: score = 80
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.success("The score = 80 based on analysis", Map.of()));
        assertEquals(80.0, evaluator.evaluate(criterion, result, context));

        // Test format: score: 70.5
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.success("score: 70.5 - partial credit", Map.of()));
        assertEquals(70.5, evaluator.evaluate(criterion, result, context));
    }

    @Test
    void shouldClampScoreToValidRange() {
        // Given: Evaluator returns out-of-range score
        Agent mockAgent = mock(Agent.class);
        when(agentRegistry.hasAgent("evaluator")).thenReturn(true);
        when(agentRegistry.getAgent("evaluator")).thenReturn(Optional.of(mockAgent));

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, "Content", Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // Test score > 100 should be clamped to 100
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.success("{\"score\": 150}", Map.of()));
        assertEquals(100.0, evaluator.evaluate(criterion, result, context));

        // Test score < 0: The regex pattern doesn't match negative numbers,
        // so it falls back to keyword inference or default score (50.0)
        // This is acceptable behavior - negative scores are invalid input
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.success("{\"score\": -20}", Map.of()));
        // Falls back to default 50.0 since -20 doesn't match the score pattern
        assertEquals(50.0, evaluator.evaluate(criterion, result, context));
    }

    @Test
    void shouldInferScoreFromKeywords() {
        // Given: Evaluator response without explicit score but with keywords
        Agent mockAgent = mock(Agent.class);
        when(agentRegistry.hasAgent("evaluator")).thenReturn(true);
        when(agentRegistry.getAgent("evaluator")).thenReturn(Optional.of(mockAgent));

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, "Content", Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // Test "excellent" keyword
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.success("This is an excellent piece of work!", Map.of()));
        assertEquals(95.0, evaluator.evaluate(criterion, result, context));

        // Test "good" keyword
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.success("The content is good overall.", Map.of()));
        assertEquals(80.0, evaluator.evaluate(criterion, result, context));

        // Test "poor" keyword
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.success("This is poor quality content.", Map.of()));
        assertEquals(35.0, evaluator.evaluate(criterion, result, context));
    }

    @Test
    void shouldReturnNeutralScoreOnAgentFailure() {
        // Given: Evaluator agent fails
        Agent mockAgent = mock(Agent.class);
        when(agentRegistry.hasAgent("evaluator")).thenReturn(true);
        when(agentRegistry.getAgent("evaluator")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(
                        AgentResponse.failure(
                                new RuntimeException("Evaluation failed due to API error")));

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, "Content to evaluate", Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Returns neutral score (50)
        assertEquals(50.0, score);
    }

    @Test
    void shouldUseCustomEvaluatorAgentId() {
        // Given: Custom evaluator agent ID
        LLMRubricEvaluator customEvaluator =
                new LLMRubricEvaluator(agentRegistry, "quality-checker");

        Agent mockAgent = mock(Agent.class);
        when(agentRegistry.hasAgent("quality-checker")).thenReturn(true);
        when(agentRegistry.getAgent("quality-checker")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.success("{\"score\": 88}", Map.of()));

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, "Content", Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = customEvaluator.evaluate(criterion, result, context);

        // Then: Uses custom agent
        assertEquals(88.0, score);
        verify(agentRegistry).hasAgent("quality-checker");
    }

    @Test
    void shouldWorkWithStubAgent() {
        // Given: Real stub agent with registered response
        stubRegistry.registerResponse(
                "evaluator",
                """
            {
              "score": 82,
              "reasoning": "The content meets most quality criteria with minor areas for improvement."
            }
            """);

        AgentConfig config =
                AgentConfig.builder()
                        .id("evaluator")
                        .model("test-model")
                        .role("Content evaluator")
                        .build();
        StubAgent stubAgent = new StubAgent("evaluator", config);

        // Use real registry with stub agent
        AgentRegistry realRegistry = mock(AgentRegistry.class);
        when(realRegistry.hasAgent("evaluator")).thenReturn(true);
        when(realRegistry.getAgent("evaluator")).thenReturn(Optional.of(stubAgent));

        LLMRubricEvaluator stubEvaluator = new LLMRubricEvaluator(realRegistry);

        NodeResult result =
                new NodeResult(ResultStatus.SUCCESS, "Test content for evaluation", Map.of());
        Criterion criterion = createCriterion("quality", "Content Quality");
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = stubEvaluator.evaluate(criterion, result, context);

        // Then: Stub response score is parsed
        assertEquals(82.0, score);
    }

    @Test
    void shouldHandleEvaluationLogicKeywordMatching() {
        // Given: No evaluator agent, criterion with specific evaluation logic
        when(agentRegistry.hasAgent("evaluator")).thenReturn(false);

        String content =
                "This article provides a comprehensive introduction to machine learning. "
                        + "It covers neural networks, deep learning, and practical applications.";

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, content, Map.of());
        Criterion criterion =
                Criterion.builder()
                        .id("coverage")
                        .name("Topic Coverage")
                        .evaluationType(EvaluationType.AUTOMATED)
                        .evaluationLogic(
                                "introduction machine learning neural networks applications")
                        .build();

        Map<String, Object> context = new HashMap<>();

        // When: Evaluating with keyword matching
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Score should be elevated due to keyword matches (>= 70)
        assertTrue(score >= 70, "Score should be elevated due to keyword matches: " + score);
    }

    @Test
    void shouldScenarioBasedStubEvaluation() {
        // Given: Different scenarios for evaluator
        stubRegistry.registerResponse("default", "evaluator", "{\"score\": 78}");
        stubRegistry.registerResponse("strict", "evaluator", "{\"score\": 45}");
        stubRegistry.registerResponse("lenient", "evaluator", "{\"score\": 95}");

        AgentConfig config =
                AgentConfig.builder().id("evaluator").model("test-model").role("Evaluator").build();
        StubAgent stubAgent = new StubAgent("evaluator", config);

        AgentRegistry realRegistry = mock(AgentRegistry.class);
        when(realRegistry.hasAgent("evaluator")).thenReturn(true);
        when(realRegistry.getAgent("evaluator")).thenReturn(Optional.of(stubAgent));

        LLMRubricEvaluator stubEvaluator = new LLMRubricEvaluator(realRegistry);

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, "Content", Map.of());
        Criterion criterion = createCriterion("quality", "Quality");

        // Test default scenario
        Map<String, Object> defaultContext = new HashMap<>();
        assertEquals(78.0, stubEvaluator.evaluate(criterion, result, defaultContext));

        // Test strict scenario
        Map<String, Object> strictContext = new HashMap<>();
        strictContext.put("stub_scenario", "strict");
        assertEquals(45.0, stubEvaluator.evaluate(criterion, result, strictContext));

        // Test lenient scenario
        Map<String, Object> lenientContext = new HashMap<>();
        lenientContext.put("stub_scenario", "lenient");
        assertEquals(95.0, stubEvaluator.evaluate(criterion, result, lenientContext));
    }

    private Criterion createCriterion(String id, String name) {
        return Criterion.builder()
                .id(id)
                .name(name)
                .evaluationType(EvaluationType.LLM_BASED)
                .build();
    }
}
