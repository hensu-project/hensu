package io.hensu.core.execution.pipeline;

/// Marker interface for a {@link NodeExecutionProcessor} that runs in the
/// POST-execution phase, after a node's primary logic has completed.
///
/// ```
/// +——————————————————————————————+
/// │ PRE-EXECUTION PIPELINE       │
/// │  (PreNodeExecutionProcessor) │
/// +——————————————————————————————+
/// │              V               │
/// │      node.execute()          │
/// │              V               │
/// +——————————————————————————————+
/// │ POST-EXECUTION PIPELINE      │
/// │ (PostNodeExecutionProcessor) │
/// +——————————————————————————————+
/// ```
///
/// ### Contracts
/// - **Precondition**: {@code context.result()} will always be non-null for all
///   processors implementing this interface
///
/// @see NodeExecutionProcessor for the base contract
/// @see PreNodeExecutionProcessor for the pre-execution counterpart
/// @see ProcessorPipeline for pipeline composition
public interface PostNodeExecutionProcessor extends NodeExecutionProcessor {}
