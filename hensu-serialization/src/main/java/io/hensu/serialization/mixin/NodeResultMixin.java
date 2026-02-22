package io.hensu.serialization.mixin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.execution.executor.NodeResult;

/// Jackson mixin that binds `NodeResult` deserialization to its builder and suppresses
/// non-serializable fields.
///
/// Applied to `NodeResult.class` via `HensuJacksonModule.setupModule()`.
/// Instructs Jackson to use `NodeResult.Builder` when deserializing, and marks the
/// `error` accessor as `@JsonIgnore` because `Throwable` is not safely round-trippable
/// across JVM boundaries or JSON snapshots.
///
/// @apiNote The companion mixin {@link NodeResultBuilderMixin} must also be registered so
/// Jackson knows how to invoke the builder's setters and `build()` method, and to suppress
/// the `error` setter on the builder side.
///
/// @implNote `NodeResult.Builder` has a private constructor. Native-image deployments
/// require `NodeResult.class` and `NodeResult.Builder.class` to be registered in
/// `NativeImageConfig` in `hensu-server`.
///
/// @see NodeResultBuilderMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonDeserialize(builder = NodeResult.Builder.class)
public abstract class NodeResultMixin {

    /// Excluded from serialization.
    ///
    /// `Throwable` is not safely round-trippable: stack frames, cause chains, and
    /// vendor-specific exception types cannot be reliably serialized to JSON and restored
    /// on deserialization. Errors are therefore treated as transient, in-memory state —
    /// not persisted in workflow snapshots.
    ///
    /// @return the execution error, may be null
    @JsonIgnore
    public abstract Throwable getError();
}
