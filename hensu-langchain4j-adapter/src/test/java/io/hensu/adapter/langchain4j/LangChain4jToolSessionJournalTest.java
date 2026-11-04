package io.hensu.adapter.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.ToolSession;
import io.hensu.core.agent.TurnJournal;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolDefinition;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/// A tool session journals its opening conversation and every answer of the model, and
/// a session opened on the same journal continues the recorded conversation without
/// asking the model again.
class LangChain4jToolSessionJournalTest {

    private static final List<ToolDefinition> TOOLS =
            List.of(
                    ToolDefinition.of(
                            "search",
                            "Search",
                            List.of(ToolDefinition.ParameterDef.required("q", "string", "Query"))));

    private static final AgentConfig CONFIG =
            AgentConfig.builder().id("agent").role("researcher").model("gpt-test").build();

    /// A model answering from a script and counting what it was asked.
    private static final class ScriptedModel implements ChatModel {
        final Deque<AiMessage> answers = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            AiMessage next = answers.poll();
            if (next == null) {
                throw new IllegalStateException("The model was asked more than the script holds");
            }
            return ChatResponse.builder().aiMessage(next).build();
        }
    }

    @Test
    void theCodecKeepsEverythingTheNextRequestDependsOn() {
        AiMessage answer =
                AiMessage.builder()
                        .text("Let me look")
                        .thinking("The user wants x")
                        .toolExecutionRequests(
                                List.of(
                                        ToolExecutionRequest.builder()
                                                .id("call_1")
                                                .name("search")
                                                .arguments("{\"q\":\"x\"}")
                                                .build()))
                        .attributes(Map.of("thinking_signature", "sig-123"))
                        .build();
        List<ChatMessage> conversation =
                List.of(
                        SystemMessage.from("You are a researcher."),
                        UserMessage.from("Find x"),
                        answer,
                        ToolExecutionResultMessage.from("call_1", "search", "x is 42"));

        assertThat(ChatMessageCodec.decode(ChatMessageCodec.encode(conversation)))
                .isEqualTo(conversation);
        assertThat(ChatMessageCodec.decodeAi(ChatMessageCodec.encode(answer))).isEqualTo(answer);
    }

    @Test
    void aTurnMissingItsTextFailsInsteadOfReplayingAnEmptyMessage() {
        assertThatThrownBy(() -> ChatMessageCodec.decode("[{\"type\":\"user\"}]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'user' message has no 'text'");
        assertThatThrownBy(
                        () ->
                                ChatMessageCodec.decode(
                                        "[{\"type\":\"tool\",\"id\":\"call_1\",\"text\":null}]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'tool' message has no 'text'");
    }

    @Test
    void aSessionOnTheSameJournalReplaysTheConversationWithoutTheModel() {
        Map<Integer, String> journal = new HashMap<>();
        TurnJournal turns = (index, live) -> journal.computeIfAbsent(index, _ -> live.get());

        ScriptedModel model = new ScriptedModel();
        model.answers.add(
                AiMessage.from(
                        List.of(
                                ToolExecutionRequest.builder()
                                        .id("call_1")
                                        .name("search")
                                        .arguments("{\"q\":\"x\"}")
                                        .build())));
        model.answers.add(AiMessage.from("x is 42"));
        LangChain4jAgent agent = new LangChain4jAgent("agent", CONFIG, model);

        ToolSession first = agent.openToolSession("Find x", Map.of(), TOOLS, turns);
        AgentResponse request = first.start();
        AgentResponse answer = first.submit(ToolCallResult.success("search", "42"));
        assertThat(model.calls).hasValue(2);

        // The recovery: a model that must not be asked anything.
        ScriptedModel silent = new ScriptedModel();
        LangChain4jAgent recovered = new LangChain4jAgent("agent", CONFIG, silent);
        ToolSession replay = recovered.openToolSession("Find x", Map.of(), TOOLS, turns);

        AgentResponse.ToolRequest replayed = (AgentResponse.ToolRequest) replay.start();
        AgentResponse.ToolRequest original = (AgentResponse.ToolRequest) request;
        assertThat(replayed.toolName()).isEqualTo(original.toolName());
        assertThat(replayed.arguments()).isEqualTo(original.arguments());
        assertThat(replay.submit(ToolCallResult.success("search", "42")))
                .extracting(r -> ((AgentResponse.TextResponse) r).content())
                .isEqualTo(((AgentResponse.TextResponse) answer).content());
        assertThat(silent.calls).hasValue(0);
    }
}
