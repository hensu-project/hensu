package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

@JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
public abstract class WorkflowBuilderMixin {}
