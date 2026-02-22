package io.hensu.serialization.mixin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/// Jackson mixin for `NodeResult.Builder` that configures POJO builder deserialization
/// and suppresses the error setter from JSON input.
///
/// Registered via `HensuJacksonModule.setupModule()` against `NodeResult.Builder.class`.
/// Sets `withPrefix = ""` so Jackson maps JSON field names directly to builder setter names
/// without a `set` or `with` prefix convention.
///
/// The `error(Throwable)` setter is marked `@JsonIgnore` so Jackson never attempts to
/// populate it from JSON — `Throwable` is not safely round-trippable and is excluded on
/// both the serialization and deserialization sides.
///
/// @apiNote The companion mixin {@link NodeResultMixin} must also be registered so Jackson
/// knows to use the builder at all.
///
/// @implNote Requires `NodeResult.Builder.class` to be registered for reflection in
/// `NativeImageConfig` in `hensu-server` (all-declared constructors + all public methods).
///
/// @see NodeResultMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonPOJOBuilder(withPrefix = "")
public abstract class NodeResultBuilderMixin {

    /// Excluded from deserialization.
    ///
    /// `Throwable` cannot be safely reconstructed from JSON. This setter is suppressed
    /// so Jackson never attempts to populate it from a snapshot. Errors are in-memory only.
    ///
    /// @param error the throwable to suppress, ignored during deserialization
    /// @return this builder for chaining, never null
    @JsonIgnore
    public abstract NodeResultBuilderMixin error(Throwable error);
}
