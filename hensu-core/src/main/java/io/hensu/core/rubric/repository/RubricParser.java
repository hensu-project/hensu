package io.hensu.core.rubric.repository;

import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.EvaluationType;
import io.hensu.core.rubric.model.Rubric;
import io.hensu.core.rubric.model.RubricType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Parses rubric Markdown files into Rubric objects.
public class RubricParser {

    private static final Pattern METADATA_PATTERN = Pattern.compile("^- (\\w+):\\s*(.+)$");
    private static final Pattern CRITERION_HEADER =
            Pattern.compile("^### (.+?)(?:\\s*\\(weight:\\s*(\\d+)\\))?$");
    private static final Pattern SUBCRITERION_HEADER = Pattern.compile("^#### (.+)$");
    private static final Pattern POINTS_PATTERN = Pattern.compile("^- points:\\s*(\\d+)$");
    private static final Pattern EVALUATION_PATTERN = Pattern.compile("^- evaluation:\\s*(.+)$");

    public static Rubric parse(Path path) {
        try {
            String content = Files.readString(path);
            return parseContent(path.getFileName().toString(), content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read rubric file: " + path, e);
        }
    }

    private static Rubric parseContent(String filename, String content) {
        ParseState state = new ParseState(filename);

        for (String line : content.split("\n")) {
            parseLine(line.trim(), state);
        }

        state.saveCurrentSubcriterion();
        state.ensureDefaultCriterion();

        return state.buildRubric();
    }

    private static void parseLine(String line, ParseState state) {
        if (parseRubricHeader(line, state)) return;
        if (parseMetadataSection(line, state)) return;
        if (state.inMetadata && parseMetadataField(line, state)) return;
        if (parseCriterionHeader(line, state)) return;
        if (parseSubcriterionHeader(line, state)) return;
        if (parsePoints(line, state)) return;
        parseEvaluation(line, state);
    }

    private static boolean parseRubricHeader(String line, ParseState state) {
        if (line.startsWith("# Rubric:")) {
            state.id = line.substring("# Rubric:".length()).trim();
            state.name = state.id;
            return true;
        }
        return false;
    }

    private static boolean parseMetadataSection(String line, ParseState state) {
        if (line.equals("## Metadata")) {
            state.inMetadata = true;
            return true;
        }
        if (line.startsWith("## ")) {
            state.inMetadata = false;
        }
        return false;
    }

    private static boolean parseMetadataField(String line, ParseState state) {
        Matcher m = METADATA_PATTERN.matcher(line);
        if (m.matches()) {
            String key = m.group(1);
            String value = m.group(2);
            switch (key) {
                case "name" -> state.name = value;
                case "version" -> state.version = value;
                case "pass_threshold" -> state.passThreshold = Double.parseDouble(value);
                case "type" -> state.type = parseType(value);
            }
            return true;
        }
        return false;
    }

    private static boolean parseCriterionHeader(String line, ParseState state) {
        Matcher matcher = CRITERION_HEADER.matcher(line);
        if (matcher.matches()) {
            state.saveCurrentSubcriterion();
            state.currentCriterionName = matcher.group(1);
            state.currentWeight =
                    matcher.group(2) != null ? Double.parseDouble(matcher.group(2)) / 100.0 : 1.0;
            state.resetSubcriterion();
            return true;
        }
        return false;
    }

    private static boolean parseSubcriterionHeader(String line, ParseState state) {
        Matcher matcher = SUBCRITERION_HEADER.matcher(line);
        if (matcher.matches()) {
            state.saveCurrentSubcriterion();
            state.currentSubcriterionName = matcher.group(1);
            state.currentPoints = 0;
            state.currentEvaluation = "";
            return true;
        }
        return false;
    }

    private static boolean parsePoints(String line, ParseState state) {
        Matcher matcher = POINTS_PATTERN.matcher(line);
        if (matcher.matches()) {
            state.currentPoints = Integer.parseInt(matcher.group(1));
            return true;
        }
        return false;
    }

    private static void parseEvaluation(String line, ParseState state) {
        Matcher matcher = EVALUATION_PATTERN.matcher(line);
        if (matcher.matches()) {
            state.currentEvaluation = matcher.group(1);
        }
    }

    private static RubricType parseType(String value) {
        return switch (value.toLowerCase()) {
            // TODO not implemented
            case "pr_review", "content_review", "security" -> RubricType.STANDARD;
            default -> RubricType.STANDARD;
        };
    }

    /// Encapsulates mutable parsing state.
    private static class ParseState {
        String id;
        String name;
        String version = "1.0.0";
        double passThreshold = 70.0;
        RubricType type = RubricType.STANDARD;

        boolean inMetadata = false;
        String currentCriterionName = null;
        double currentWeight = 1.0;
        String currentSubcriterionName = null;
        int currentPoints = 0;
        String currentEvaluation = "";

        final List<Criterion> criteria = new ArrayList<>();

        ParseState(String filename) {
            this.id = filename.replace(".md", "");
            this.name = this.id;
        }

        void resetSubcriterion() {
            currentSubcriterionName = null;
            currentPoints = 0;
            currentEvaluation = "";
        }

        void saveCurrentSubcriterion() {
            if (currentSubcriterionName != null) {
                criteria.add(
                        buildCriterion(
                                currentCriterionName,
                                currentSubcriterionName,
                                currentPoints,
                                currentWeight,
                                currentEvaluation));
            }
        }

        void ensureDefaultCriterion() {
            if (criteria.isEmpty()) {
                criteria.add(
                        Criterion.builder()
                                .id("default")
                                .name("Overall Quality")
                                .weight(1.0)
                                .evaluationType(EvaluationType.LLM_BASED)
                                .build());
            }
        }

        Rubric buildRubric() {
            return Rubric.builder()
                    .id(id)
                    .name(name)
                    .version(version)
                    .type(type)
                    .passThreshold(passThreshold)
                    .criteria(criteria)
                    .build();
        }
    }

    private static Criterion buildCriterion(
            String category, String name, int points, double categoryWeight, String evaluation) {
        String id = (category + "_" + name).toLowerCase().replaceAll("\\s+", "_");
        return Criterion.builder()
                .id(id)
                .name(name)
                .description(evaluation)
                .weight(points * categoryWeight)
                .evaluationType(EvaluationType.LLM_BASED)
                .evaluationLogic(evaluation)
                .build();
    }
}
