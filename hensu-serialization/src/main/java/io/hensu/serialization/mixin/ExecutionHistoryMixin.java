package io.hensu.serialization.mixin;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/// Mixin enabling Jackson field-level access for ExecutionHistory.
///
/// ExecutionHistory has no builder and no setters — only a no-arg constructor
/// and private fields. This mixin allows Jackson to read/write fields directly.
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public abstract class ExecutionHistoryMixin {}
