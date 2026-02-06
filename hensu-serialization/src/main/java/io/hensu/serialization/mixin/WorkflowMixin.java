package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.workflow.Workflow;

@JsonDeserialize(builder = Workflow.Builder.class)
public abstract class WorkflowMixin {}
