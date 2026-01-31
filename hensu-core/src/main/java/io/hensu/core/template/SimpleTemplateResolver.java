package io.hensu.core.template;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Simple template resolver using regex.
public class SimpleTemplateResolver implements TemplateResolver {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{([^}]+)}");

    @Override
    public String resolve(String template, Map<String, Object> context) {
        if (template == null) {
            return "";
        }

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String variable = matcher.group(1);
            Object value = context.get(variable);
            String replacement = value != null ? value.toString() : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }
}
