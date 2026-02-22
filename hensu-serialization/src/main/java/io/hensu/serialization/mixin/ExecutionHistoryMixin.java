package io.hensu.serialization.mixin;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/// Jackson mixin enabling field-level visibility for `ExecutionHistory` serialization.
///
/// `ExecutionHistory` follows a different construction pattern than other domain objects:
/// it has a no-arg constructor and private mutable fields rather than a builder. Standard
/// Jackson visibility rules (`PUBLIC_ONLY`) cannot see those fields, so this mixin widens
/// access to `ANY` via `@JsonAutoDetect(fieldVisibility = Visibility.ANY)`.
///
/// Unlike the `*Mixin` / `*BuilderMixin` pairs used for builder-pattern types, no builder
/// mixin companion is needed — Jackson reads and writes fields directly.
///
/// @implNote This mixin does **not** require builder constructor registration in
/// `NativeImageConfig`. Jackson's field-access mechanism resolves fields via the class
/// itself, not via builder reflection calls.
///
/// @see io.hensu.serialization.HensuJacksonModule
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public abstract class ExecutionHistoryMixin {}
