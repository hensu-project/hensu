# 01-context-strategy.md

# Pommel - Semantic Code Search

## Overview

Pommel is a semantic code search tool designed as a first-line discovery method. It returns semantic matches with
approximately 18x fewer tokens than grep-based searching.

## When to Use

**Use `pm search` for:**

- Finding implementations
- Locating where specific functionality is handled
- Iterative code exploration
- General code lookups (recommended as first attempt)

**Use grep/file explorer instead for:**

- Verifying something does NOT exist
- Searching exact string literals and error messages
- Understanding architecture and code flow
- Needing full file context

## Search Syntax Examples

```bash
pm search "IPC handler for updates"
pm search "validation logic" --path src/shared
pm search "state management" --level function,method
pm search "error handling" --json --limit 5
pm search "authentication" --metrics
```

## Score Interpretation

- **> 0.7**: Strong match - use directly
- **0.5-0.7**: Moderate match - review snippet, may require additional reading
- **< 0.5**: Weak match - try different query or use grep

## Key Command Flags

- `--path <prefix>`: Scope results to specific directory
- `--level <types>`: Filter by code structure (file, class, function, method, block)
- `--limit N`: Limit number of results (default: 10)
- `--verbose`: Display match reasoning
- `--json`: Structured output format

## Other Commands

- `pm status`: Check daemon status and index statistics
- `pm reindex`: Force a full reindex of the codebase
