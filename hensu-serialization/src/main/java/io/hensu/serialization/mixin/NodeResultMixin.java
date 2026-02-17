package io.hensu.serialization.mixin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.execution.executor.NodeResult;

@JsonDeserialize(builder = NodeResult.Builder.class)
public abstract class NodeResultMixin {

    /// Throwable is not safely serializable/deserializable.
    /// Errors are transient — not persisted in snapshots.
    @JsonIgnore
    public abstract Throwable getError();
}
