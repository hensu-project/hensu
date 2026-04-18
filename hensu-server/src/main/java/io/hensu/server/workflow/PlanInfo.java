package io.hensu.server.workflow;

/// Information about a pending plan.
///
/// @param planId the plan identifier, never null
/// @param totalSteps total number of steps in the plan
/// @param currentStep index of the step currently executing
public record PlanInfo(String planId, int totalSteps, int currentStep) {}
