package io.hensu.server.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

/// GraalVM native image reflection registrations for LangChain4j Google AI Gemini DTO classes.
///
/// ### Why manual registration is required
///
/// `quarkus-langchain4j-ai-gemini`'s `AiGeminiProcessor` build step only generates CDI beans
/// (`SyntheticBeanBuildItem`). It registers **zero** reflection metadata for the underlying
/// DTO types. This is correct for the CDI path — Quarkus's Jackson extension processes
/// `@JsonProperty` annotations on discovered beans at build time. However, `LangChain4jProvider`
/// creates `GoogleAiGeminiChatModel` **programmatically**, outside CDI, so that build-time
/// scan never fires.
///
/// Additionally, `dev.langchain4j.model.googleai.Json` owns a **static** `ObjectMapper`:
///
/// ```
/// static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
/// ```
///
/// This instance is independent of the Quarkus-managed `ObjectMapper` and therefore receives
/// none of Quarkus's build-time Jackson configuration. At runtime in native mode, Jackson
/// calls `Class.getDeclaredFields()` on each DTO being serialized. Without reflection
/// registration, GraalVM returns an empty field list — Jackson produces `{}` for every DTO,
/// the `contents` array in `GenerateContentRequest` serializes as empty objects, and the
/// Gemini API responds with:
///
/// ```
/// 400 GenerateContentRequest.contents: contents is not specified
/// ```
///
/// ### Coverage
///
/// All request-path DTOs (Java → JSON serialization) and response-path DTOs
/// (JSON → Java deserialization) are registered so a single native build covers the complete
/// request/response cycle. Enums are included because Jackson resolves their names via
/// `Enum.name()` backed by reflection in native mode.
///
/// Builder inner classes (`Gemini*Builder`) are intentionally **omitted** — LangChain4j's
/// `Json.OBJECT_MAPPER` uses direct field access, not the Lombok builder pattern, for
/// Jackson binding.
///
/// @implNote Quarkus merges reflection metadata from all `@RegisterForReflection` classes at
/// build time. This class is intentionally separate from {@link NativeImageConfig} to keep
/// third-party registrations isolated and to prevent that file from growing unbounded.
/// @see NativeImageConfig for Hensu domain model and JDK HTTP client registrations
@RegisterForReflection(
        classNames = {
            // --- Request DTOs (serialization: Java → JSON → Gemini API) ---
            "dev.langchain4j.model.googleai.GeminiGenerateContentRequest",
            "dev.langchain4j.model.googleai.GeminiContent",
            "dev.langchain4j.model.googleai.GeminiPart",
            "dev.langchain4j.model.googleai.GeminiBlob",
            "dev.langchain4j.model.googleai.GeminiFileData",
            "dev.langchain4j.model.googleai.GeminiGenerationConfig",
            "dev.langchain4j.model.googleai.GeminiThinkingConfig",
            "dev.langchain4j.model.googleai.GeminiSafetySetting",
            "dev.langchain4j.model.googleai.GeminiTool",
            "dev.langchain4j.model.googleai.GeminiFunctionDeclaration",
            "dev.langchain4j.model.googleai.GeminiSchema",
            "dev.langchain4j.model.googleai.GeminiToolConfig",
            "dev.langchain4j.model.googleai.GeminiFunctionCallingConfig",
            "dev.langchain4j.model.googleai.GeminiCachedContent",
            "dev.langchain4j.model.googleai.GeminiCodeExecution",
            // --- Response DTOs (deserialization: Gemini API → JSON → Java) ---
            "dev.langchain4j.model.googleai.GeminiGenerateContentResponse",
            "dev.langchain4j.model.googleai.GeminiCandidate",
            "dev.langchain4j.model.googleai.GeminiUsageMetadata",
            "dev.langchain4j.model.googleai.GeminiPromptFeedback",
            "dev.langchain4j.model.googleai.GeminiSafetyRating",
            "dev.langchain4j.model.googleai.GeminiCitationMetadata",
            "dev.langchain4j.model.googleai.GeminiCitationSource",
            "dev.langchain4j.model.googleai.GeminiFunctionCall",
            "dev.langchain4j.model.googleai.GeminiFunctionResponse",
            "dev.langchain4j.model.googleai.GeminiExecutableCode",
            "dev.langchain4j.model.googleai.GeminiCodeExecutionResult",
            "dev.langchain4j.model.googleai.GeminiGroundingAttribution",
            "dev.langchain4j.model.googleai.GeminiGroundingPassageId",
            "dev.langchain4j.model.googleai.GeminiAttributionSourceId",
            "dev.langchain4j.model.googleai.GeminiSemanticRetrieverChunk",
            "dev.langchain4j.model.googleai.GeminiError",
            "dev.langchain4j.model.googleai.GeminiErrorContainer",
            "dev.langchain4j.model.googleai.GeminiCountTokensRequest",
            "dev.langchain4j.model.googleai.GeminiCountTokensResponse",
            // --- Enums (Jackson resolves names via reflection in native mode) ---
            "dev.langchain4j.model.googleai.GeminiRole",
            "dev.langchain4j.model.googleai.GeminiMode",
            "dev.langchain4j.model.googleai.GeminiHarmCategory",
            "dev.langchain4j.model.googleai.GeminiHarmBlockThreshold",
            "dev.langchain4j.model.googleai.GeminiHarmProbability",
            "dev.langchain4j.model.googleai.GeminiBlockReason",
            "dev.langchain4j.model.googleai.GeminiType",
            "dev.langchain4j.model.googleai.GeminiFinishReason",
            "dev.langchain4j.model.googleai.GeminiLanguage",
            "dev.langchain4j.model.googleai.GeminiOutcome"
        })
public class LangChain4jGeminiNativeConfig {}
