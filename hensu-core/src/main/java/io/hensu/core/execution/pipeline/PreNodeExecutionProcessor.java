package io.hensu.core.execution.pipeline;

/// Marker interface for a {@link NodeExecutionProcessor} that runs in the
/// PRE-execution phase, before a node's primary logic is invoked.
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
/// - **Precondition**: {@code context.result()} will be {@code null} for all
///   processors implementing this interface
///
/// @see NodeExecutionProcessor for the base contract
/// @see PostNodeExecutionProcessor for the post-execution counterpart
/// @see ProcessorPipeline for pipeline composition
public interface PreNodeExecutionProcessor extends NodeExecutionProcessor {}
