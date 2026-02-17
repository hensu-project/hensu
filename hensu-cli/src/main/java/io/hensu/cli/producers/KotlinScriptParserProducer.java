package io.hensu.cli.producers;

import io.hensu.dsl.parsers.KotlinScriptParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/// CDI producer for the Kotlin DSL workflow parser.
///
/// Creates a singleton instance of {@link KotlinScriptParser} for parsing `.kt` workflow files.
/// The parser compiles Kotlin scripts using the embedded Kotlin compiler.
///
/// @implNote Application-scoped for performance; parser initialization is expensive.
/// @see io.hensu.cli.commands.WorkflowCommand
@ApplicationScoped
public class KotlinScriptParserProducer {

    /// Produces a Kotlin script parser instance for CDI injection.
    ///
    /// @return singleton parser instance, never null
    @Produces
    @ApplicationScoped
    public KotlinScriptParser kotlinScriptParser() {
        return new KotlinScriptParser();
    }
}
