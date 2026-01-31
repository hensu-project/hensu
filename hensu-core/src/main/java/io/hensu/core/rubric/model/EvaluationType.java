package io.hensu.core.rubric.model;

public enum EvaluationType {
    AUTOMATED, // Automated checks (regex, file exists, etc.)
    MANUAL, // Human evaluation
    LLM_BASED, // LLM evaluation
    HYBRID // Combination of automated + LLM
}
