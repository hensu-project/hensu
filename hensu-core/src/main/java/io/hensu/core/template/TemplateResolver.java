package io.hensu.core.template;

import java.util.Map;

/// Resolves template variables in strings. Pure utility, no dependencies.
public interface TemplateResolver {
    String resolve(String template, Map<String, Object> context);
}
