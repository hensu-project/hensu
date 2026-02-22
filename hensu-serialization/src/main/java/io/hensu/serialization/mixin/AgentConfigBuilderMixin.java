package io.hensu.serialization.mixin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/// Jackson mixin for `AgentConfig.Builder` that configures POJO builder deserialization
/// and corrects a boolean getter naming mismatch.
///
/// Registered via `HensuJacksonModule.setupModule()` against `AgentConfig.Builder.class`.
/// Sets `withPrefix = ""` so Jackson maps JSON field names directly to builder setter names
/// without a `set` or `with` prefix convention.
///
/// Also corrects a naming mismatch: the boolean getter `isMaintainContext()` serializes the
/// field as `"maintainContext"`, but without an explicit `@JsonProperty`, Jackson cannot
/// automatically correlate the `is`-prefixed getter back to the unprefixed builder setter
/// during deserialization. The `@JsonProperty("maintainContext")` on
/// {@link #maintainContext(boolean)} pins the mapping explicitly.
///
/// @apiNote The companion mixin {@link AgentConfigMixin} must also be registered so Jackson
/// knows to use the builder at all.
///
/// @implNote Requires `AgentConfig.Builder.class` to be registered for reflection in
/// `NativeImageConfig` in `hensu-server` (all-declared constructors + all public methods).
///
/// @see AgentConfigMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonPOJOBuilder(withPrefix = "")
public abstract class AgentConfigBuilderMixin {

    /// Maps the JSON field `"maintainContext"` to the builder setter.
    ///
    /// Explicit binding is required because the corresponding getter is `isMaintainContext()`,
    /// and Jackson cannot automatically correlate the `is`-prefixed getter to the
    /// unprefixed setter without this annotation.
    ///
    /// @param maintainContext whether the agent should maintain conversation context
    ///                        across invocations
    /// @return this builder for chaining, never null
    @JsonProperty("maintainContext")
    public abstract AgentConfigBuilderMixin maintainContext(boolean maintainContext);
}
