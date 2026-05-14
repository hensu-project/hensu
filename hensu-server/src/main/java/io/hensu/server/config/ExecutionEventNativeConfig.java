package io.hensu.server.config;

import io.hensu.server.streaming.ExecutionEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;

/// GraalVM native image reflection registrations for SSE execution event records.
///
/// {@link ExecutionEvent} is a sealed interface whose permitted record subtypes are
/// serialized to JSON inside a {@code Multi<ExecutionEvent>} SSE stream. Quarkus
/// build-time analysis resolves the JAX-RS return type to the sealed interface but
/// does not automatically walk the permitted subtypes for Jackson serialization
/// metadata. Without explicit registration, record component accessors are
/// unreachable in native mode and all fields serialize as {@code null}.
///
/// The {@code type()} accessor works without registration because it carries an
/// explicit {@code @JsonProperty("type")} annotation that Quarkus traces at build
/// time; the remaining component accessors rely on default Jackson record support
/// which requires reflective access to canonical constructors and accessors.
///
/// @see ExecutionEvent for the sealed hierarchy
/// @see io.hensu.server.api.ExecutionEventResource for the SSE endpoint
/// @see CoreModelNativeConfig for {@code hensu-core} domain model registrations
@RegisterForReflection(
        targets = {
            ExecutionEvent.ExecutionStarted.class,
            ExecutionEvent.PlanCreated.class,
            ExecutionEvent.StepStarted.class,
            ExecutionEvent.StepCompleted.class,
            ExecutionEvent.PlanRevised.class,
            ExecutionEvent.PlanCompleted.class,
            ExecutionEvent.ExecutionPaused.class,
            ExecutionEvent.ExecutionCompleted.class,
            ExecutionEvent.ExecutionError.class,
            ExecutionEvent.StepInfo.class
        })
public class ExecutionEventNativeConfig {}
