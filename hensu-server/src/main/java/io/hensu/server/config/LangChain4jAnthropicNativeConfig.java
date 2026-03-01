package io.hensu.server.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

/// GraalVM native image reflection registrations for LangChain4j Anthropic DTO classes.
///
/// ### Why manual registration is required
///
/// `quarkus-langchain4j-anthropic`'s `AnthropicProcessor` build step only generates CDI beans
/// (`SyntheticBeanBuildItem`). Unlike the OpenAI counterpart (`OpenAiProcessor.nativeSupport`),
/// it registers **zero** reflection metadata for the underlying DTO types. `LangChain4jProvider`
/// creates `AnthropicChatModel` programmatically, outside CDI, so Quarkus build-time Jackson
/// processing never fires for these classes.
///
/// Additionally, `dev.langchain4j.model.anthropic.internal.client.Json` owns a **static**
/// `ObjectMapper` independent of the Quarkus-managed one. At runtime in native mode, Jackson
/// calls `Class.getDeclaredFields()` on each DTO being serialized — without reflection
/// registration, GraalVM returns an empty field list and the HTTP request body is empty.
///
/// ### Coverage
///
/// All request-path DTOs (`AnthropicCreateMessageRequest` and its nested content types) and
/// response-path DTOs are registered. Enums are included because Jackson resolves their names
/// via reflection in native mode. Builder inner classes are intentionally **omitted** —
/// `Json.OBJECT_MAPPER` uses direct field access, not the builder pattern, for Jackson binding.
///
/// @implNote Quarkus merges reflection metadata from all `@RegisterForReflection` classes at
/// build time. This class is intentionally separate from {@link NativeImageConfig} to keep
/// third-party registrations isolated.
/// @see LangChain4jNativeConfig for shared LangChain4j transport registrations
/// @see LangChain4jGeminiNativeConfig for Google AI Gemini DTO registrations
/// @see NativeImageConfig for Hensu domain model registrations
@RegisterForReflection(
        classNames = {
            // --- Request DTOs (serialization: Java → JSON → Anthropic API) ---
            "dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicMessage",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicMessageContent",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicTextContent",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicImageContent",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicImageContentSource",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicToolUseContent",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicToolResultContent",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicThinkingContent",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicRedactedThinkingContent",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicPdfContent",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicPdfContentSource",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicTool",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicToolSchema",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicToolChoice",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicMetadata",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicCacheControl",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicCountTokensRequest",
            // --- Response DTOs (deserialization: Anthropic API → JSON → Java) ---
            "dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicContent",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicResponseMessage",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicUsage",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicDelta",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicStreamingData",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicThinking",
            "dev.langchain4j.model.anthropic.internal.api.MessageTokenCountResponse",
            // --- Enums (Jackson resolves names via reflection in native mode) ---
            "dev.langchain4j.model.anthropic.internal.api.AnthropicRole",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicCacheType",
            "dev.langchain4j.model.anthropic.internal.api.AnthropicToolChoiceType"
        })
public class LangChain4jAnthropicNativeConfig {}
