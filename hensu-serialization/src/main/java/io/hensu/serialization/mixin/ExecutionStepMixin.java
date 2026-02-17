package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.execution.result.ExecutionStep;

@JsonDeserialize(builder = ExecutionStep.Builder.class)
public abstract class ExecutionStepMixin {}
