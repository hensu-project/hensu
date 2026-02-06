package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.agent.AgentConfig;

@JsonDeserialize(builder = AgentConfig.Builder.class)
public abstract class AgentConfigMixin {}
