package io.hensu.core.execution.parallel;

/// Typed sentinel placed in merged output maps to represent a branch or fork that
/// failed or returned a null output.
///
/// Downstream nodes can pattern-match on this type to distinguish real outputs from
/// failures without relying on null checks or stringly-typed sentinels.
///
/// ```
/// +———————————————————————————————————+
/// │  Map<String, Object> outputs      │
/// │                                   │
/// │  "branch-a"  ————>  "result"      │  (success)
/// │  "branch-b"  ————>  FailureMarker │
/// │                     ("timed out") │  (failed)
/// +———————————————————————————————————+
/// ```
///
/// @param message human-readable description of why the branch/fork failed
public record FailureMarker(String message) {}
