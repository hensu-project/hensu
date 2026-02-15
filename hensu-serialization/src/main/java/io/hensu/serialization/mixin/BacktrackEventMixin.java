package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.execution.result.BacktrackEvent;

@JsonDeserialize(builder = BacktrackEvent.Builder.class)
public abstract class BacktrackEventMixin {}
