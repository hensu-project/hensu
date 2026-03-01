package io.hensu.server.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

/// GraalVM native image reflection registrations for LangChain4j shared transport
/// infrastructure used by all model providers (Anthropic, OpenAI, Google AI Gemini).
///
/// ### Why manual registration is required
///
/// `LangChain4jProvider` creates all `ChatModel` instances programmatically via builders,
/// outside Quarkus CDI. The Quarkiverse extensions (`quarkus-langchain4j-anthropic`,
/// `quarkus-langchain4j-openai`, `quarkus-langchain4j-ai-gemini`) wire their HTTP clients
/// through CDI — that path is invisible to `LangChain4jProvider`.
///
/// The HTTP client is resolved at runtime via `ServiceLoader`:
/// `dev.langchain4j.http.client.HttpClientBuilderLoader` scans
/// `META-INF/services/dev.langchain4j.http.client.HttpClientBuilderFactory`. GraalVM cannot
/// trace `ServiceLoader` statically, so two things are required:
///
/// 1. The service file must be included in the native image resources
///    (`quarkus.native.resources.includes` in `application.properties`).
/// 2. The implementation classes must be registered for reflection so GraalVM can
///    instantiate them at runtime (registered here).
///
/// @implNote Quarkus merges reflection metadata from all `@RegisterForReflection` classes at
/// build time. This class covers only the shared transport layer. Provider-specific DTO
/// registrations live in dedicated classes (e.g., {@link LangChain4jGeminiNativeConfig}).
/// @see LangChain4jGeminiNativeConfig for Google AI Gemini DTO registrations
/// @see NativeImageConfig for Hensu domain model registrations
@RegisterForReflection(
        classNames = {
            // JDK HTTP client — ServiceLoader instantiates these via reflection.
            // All three are required: the factory (entry point), the builder, and the client.
            "dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory",
            "dev.langchain4j.http.client.jdk.JdkHttpClientBuilder",
            "dev.langchain4j.http.client.jdk.JdkHttpClient"
        })
public class LangChain4jNativeConfig {}
