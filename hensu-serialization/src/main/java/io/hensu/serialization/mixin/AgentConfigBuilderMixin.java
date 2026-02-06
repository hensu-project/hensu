package io.hensu.serialization.mixin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/// Mixin for `AgentConfig.Builder` to handle the naming mismatch:
/// getter `isMaintainContext()` serializes as `"maintainContext"`,
/// but the builder setter is `maintainContext(boolean)`.
@JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
public abstract class AgentConfigBuilderMixin {

    @JsonProperty("maintainContext")
    public abstract AgentConfigBuilderMixin maintainContext(boolean maintainContext);
}
