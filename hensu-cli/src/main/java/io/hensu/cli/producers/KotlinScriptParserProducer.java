package io.hensu.cli.producers;

import io.hensu.dsl.parsers.KotlinScriptParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class KotlinScriptParserProducer {
    @Produces
    @ApplicationScoped
    public KotlinScriptParser kotlinScriptParser() {
        return new KotlinScriptParser();
    }
}
