# Hensu Serialization

Jackson-based JSON serialization for the Hensu workflow type hierarchy.

## Overview

The `hensu-serialization` module provides JSON serialization and deserialization for all Hensu domain types.
It bridges `hensu-core`'s builder-pattern classes with Jackson using explicit mixins and custom
serializer/deserializer pairs — no reflection scanning, GraalVM native-image safe.

## Entry Point

`WorkflowSerializer` provides the convenience API:

```java
// Serialize a workflow to JSON
String json = WorkflowSerializer.toJson(workflow);

// Deserialize from JSON
Workflow restored = WorkflowSerializer.fromJson(json);

// Get a pre-configured ObjectMapper for direct use
ObjectMapper mapper = WorkflowSerializer.createMapper();
```

The mapper registers:
- `HensuJacksonModule` — custom serializers, deserializers, and mixins
- `JavaTimeModule` — `Instant`, `Duration` as ISO-8601 strings
- `FAIL_ON_UNKNOWN_PROPERTIES` disabled for forward compatibility

## Serialization Strategy

### Custom Serializers (Polymorphic Types)

Types with sealed hierarchies need custom serializer/deserializer pairs because Jackson
cannot resolve subtypes without reflection or annotations on the sealed interface itself:

| Type             | Serializer                  | Deserializer                  |
|------------------|-----------------------------|-------------------------------|
| `Node`           | `NodeSerializer`            | `NodeDeserializer`            |
| `TransitionRule` | `TransitionRuleSerializer`  | `TransitionRuleDeserializer`  |
| `Action`         | `ActionSerializer`          | `ActionDeserializer`          |
| `PlanStepAction` | `PlanStepActionSerializer`  | `PlanStepActionDeserializer`  |

Each serializer writes a `"type"` discriminator field. The deserializer reads it to select the concrete class.

### Mixins (Builder-Pattern Types)

Types using the builder pattern are handled via Jackson mixins — no annotations on core classes:

| Core Type          | Mixin                   | Strategy                                                                                  |
|--------------------|-------------------------|-------------------------------------------------------------------------------------------|
| `Workflow`         | `WorkflowMixin`         | `@JsonDeserialize(builder = Workflow.Builder)`                                            |
| `AgentConfig`      | `AgentConfigMixin`      | `@JsonDeserialize(builder = AgentConfig.Builder)`                                         |
| `ExecutionStep`    | `ExecutionStepMixin`    | `@JsonDeserialize(builder = ExecutionStep.Builder)`                                       |
| `NodeResult`       | `NodeResultMixin`       | `@JsonDeserialize(builder)` + `@JsonIgnore` on `getError()` and `error(Throwable)` setter |
| `BacktrackEvent`   | `BacktrackEventMixin`   | `@JsonDeserialize(builder = BacktrackEvent.Builder)`                                      |
| `ExecutionHistory` | `ExecutionHistoryMixin` | `@JsonAutoDetect(fieldVisibility = ANY)` — no builder                                     |

Builder mixins use `@JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")` to match
the fluent builder API (e.g., `.nodeId("x")` not `.withNodeId("x")`).

**`NodeResult.getError()`** is `@JsonIgnore`d because `Throwable` is not safely serializable.

### Plain Records (Default Jackson Serialization)

Java records with public component accessors are serialized by Jackson's default `BeanSerializer`
without requiring custom serializers or mixins. However, in GraalVM native image, Jackson cannot
discover record accessors unless they are registered for reflection.

These types are deserialized manually in `NodeDeserializer` (direct `JsonNode` extraction), but
serialized via default Jackson machinery in `WorkflowSerializer.toJson()`. Both paths must work:

| Type                       | Context                                                            |
|----------------------------|--------------------------------------------------------------------|
| `Branch`                   | Embedded in `ParallelNode` – includes `yields` (List of String)    |
| `ConsensusConfig`          | Embedded in `ParallelNode` – strategy, threshold, judge agent ID   |
| `ConsensusStrategy`        | Enum inside `ConsensusConfig`                                      |
| `ConsensusResult`          | Stored in state context during checkpoint serialization            |
| `ConsensusResult.Vote`     | Inner record inside `ConsensusResult`                              |
| `ConsensusResult.VoteType` | Enum inside `ConsensusResult.Vote`                                 |
| `ScoreCondition`           | Embedded in `ScoreTransition` via `NodeDeserializer`               |
| `DoubleRange`              | Embedded in `ScoreCondition`                                       |

All are registered in `NativeImageConfig` in `hensu-server`. Do **not** create mixins for these –
reflection registration is sufficient since they have public constructors and accessors.
Errors are transient and not persisted in snapshots. The builder mixin (`NodeResultBuilderMixin`)
also suppresses the `error(Throwable)` setter with `@JsonIgnore` — this prevents Jackson from
crashing when deserializing a snapshot payload that contains no `error` field. Both halves of
the ignore pattern are required; removing either one breaks round-trip deserialization.

## Module Structure

```
hensu-serialization/src/main/java/io/hensu/serialization/
├── WorkflowSerializer.java          # Convenience API (toJson/fromJson/createMapper)
├── HensuJacksonModule.java          # Registers all serializers and mixins
├── NodeSerializer.java              # Node sealed hierarchy serializer
├── NodeDeserializer.java            # Node sealed hierarchy deserializer
├── TransitionRuleSerializer.java    # TransitionRule sealed hierarchy serializer
├── TransitionRuleDeserializer.java  # TransitionRule sealed hierarchy deserializer
├── ActionSerializer.java            # Action sealed hierarchy serializer
├── ActionDeserializer.java          # Action sealed hierarchy deserializer
├── PlanStepActionSerializer.java    # PlanStepAction sealed hierarchy serializer
├── PlanStepActionDeserializer.java  # PlanStepAction sealed hierarchy deserializer
├── WorkflowStateSchemaDeserializer.java  # Direct-extraction deserializer (native-image perf)
├── plan/
│   └── JacksonPlanResponseParser.java  # Parses LLM JSON responses into PlannedStep lists
└── mixin/
    ├── WorkflowMixin.java           # Workflow builder deserialization
    ├── WorkflowBuilderMixin.java
    ├── AgentConfigMixin.java        # AgentConfig builder deserialization
    ├── AgentConfigBuilderMixin.java
    ├── ExecutionStepMixin.java      # ExecutionStep builder deserialization
    ├── ExecutionStepBuilderMixin.java
    ├── NodeResultMixin.java         # NodeResult builder + @JsonIgnore error
    ├── NodeResultBuilderMixin.java
    ├── BacktrackEventMixin.java     # BacktrackEvent builder deserialization
    ├── BacktrackEventBuilderMixin.java
    └── ExecutionHistoryMixin.java   # Field-level access (no builder)
```

## Dependencies

- `hensu-core` (API dependency)
- Jackson BOM 2.20.1 (`jackson-databind`, `jackson-datatype-jsr310`)
- Test: JUnit 5, AssertJ

## See Also

- [Serialization Developer Guide](../docs/developer-guide-serialization.md) — `treeToValue` rule, GraalVM registration, round-trip testing patterns
