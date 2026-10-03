package io.hensu.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Describes a callable tool without implementation details.
///
/// Tool definitions are protocol-agnostic descriptors used by:
/// - Agents, to decide which tool to request
/// - The tool loop, to validate the tool an agent asked for
/// - MCP integration at the server layer
///
/// The core module only defines the tool shape; actual invocation happens
/// through {@link ToolProvider} implementations contributed by each runtime.
///
/// ### Two Views Of The Same Inputs
/// `parameters` is a flat projection an engine can reason about: the tool loop
/// checks required names against it, the approval frame lists them, and the
/// router uses them to describe a call. It is lossy by construction – it has no
/// way to say "one of these three strings", "an array of objects", or "at least
/// one of these keys". `rawSchema` is the JSON Schema the source published,
/// carried verbatim so an adapter can hand the model exactly what the tool
/// actually accepts. A tool declared locally has none and rebuilds a schema from
/// `parameters`; a tool discovered over MCP has both.
///
/// ### Contracts
/// - **Precondition**: `name` must not be null or blank
/// - **Postcondition**: All fields immutable after construction
///
/// ### Usage
/// {@snippet :
/// ToolDefinition searchTool = ToolDefinition.of(
///     "search",
///     "Search for information",
///     List.of(
///         new ParameterDef("query", "string", "Search query", true, null, false)
///     ),
///     new ParameterDef("results", "array", "Search results", true, null, false)
/// );
/// }
///
/// @param name unique tool identifier, not null
/// @param description human-readable description for LLM context, not null
/// @param parameters input parameters accepted by the tool, not null (may be empty)
/// @param returnType description of the tool's output, may be null
/// @param rawSchema the JSON Schema the source published for the tool's input,
///     may be null when the source published none
/// @see ToolRegistry for tool discovery
/// @see ToolProvider for tool invocation
public record ToolDefinition(
        String name,
        String description,
        List<ParameterDef> parameters,
        ParameterDef returnType,
        Map<String, Object> rawSchema) {

    /// Compact constructor with validation.
    public ToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(description, "description must not be null");
        parameters = parameters != null ? List.copyOf(parameters) : List.of();
        // Not Map.copyOf: a published schema legitimately carries JSON nulls
        // (a "default": null, for instance), which Map.copyOf rejects outright.
        rawSchema =
                rawSchema != null
                        ? Collections.unmodifiableMap(new LinkedHashMap<>(rawSchema))
                        : null;
    }

    /// Creates a tool definition without a published schema.
    ///
    /// @param name unique tool identifier, not null
    /// @param description human-readable description, not null
    /// @param parameters input parameters, not null
    /// @param returnType description of the tool's output, may be null
    /// @return new tool definition, never null
    public static ToolDefinition of(
            String name,
            String description,
            List<ParameterDef> parameters,
            ParameterDef returnType) {
        return new ToolDefinition(name, description, parameters, returnType, null);
    }

    /// Creates a tool definition without explicit return type.
    ///
    /// @param name unique tool identifier, not null
    /// @param description human-readable description, not null
    /// @param parameters input parameters, not null
    /// @return new tool definition, never null
    public static ToolDefinition of(
            String name, String description, List<ParameterDef> parameters) {
        return new ToolDefinition(name, description, parameters, null, null);
    }

    /// Creates a simple tool definition with no parameters.
    ///
    /// @param name unique tool identifier, not null
    /// @param description human-readable description, not null
    /// @return new tool definition, never null
    public static ToolDefinition simple(String name, String description) {
        return new ToolDefinition(name, description, List.of(), null, null);
    }

    /// Returns whether this tool has any required parameters.
    ///
    /// @return true if any parameter is required
    public boolean hasRequiredParameters() {
        return parameters.stream().anyMatch(ParameterDef::required);
    }

    /// Returns the list of required parameter names.
    ///
    /// @return list of required parameter names, never null
    public List<String> requiredParameterNames() {
        return parameters.stream().filter(ParameterDef::required).map(ParameterDef::name).toList();
    }

    /// Describes a tool parameter or return type.
    ///
    /// Sensitive parameters are redacted at the source: the tool loop replaces
    /// their values with a placeholder before the audit record leaves the core,
    /// so no listener, log or durable sink ever receives the secret while the
    /// provider still receives the real value.
    ///
    /// @param name parameter identifier, not null
    /// @param type parameter type (string, number, boolean, object, array), not null
    /// @param description human-readable description, not null
    /// @param required whether the parameter must be provided
    /// @param defaultValue default value if not provided, may be null
    /// @param sensitive whether the value carries a secret and must never be audited
    /// @see io.hensu.core.tool.ToolCallEvent#REDACTED for the replacement value
    public record ParameterDef(
            String name,
            String type,
            String description,
            boolean required,
            Object defaultValue,
            boolean sensitive) {

        /// Compact constructor with validation.
        public ParameterDef {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(description, "description must not be null");
        }

        /// Creates a required parameter definition.
        ///
        /// @param name parameter identifier, not null
        /// @param type parameter type, not null
        /// @param description human-readable description, not null
        /// @return new parameter definition, never null
        public static ParameterDef required(String name, String type, String description) {
            return new ParameterDef(name, type, description, true, null, false);
        }

        /// Creates an optional parameter definition.
        ///
        /// @param name parameter identifier, not null
        /// @param type parameter type, not null
        /// @param description human-readable description, not null
        /// @param defaultValue default value if not provided, may be null
        /// @return new parameter definition, never null
        public static ParameterDef optional(
                String name, String type, String description, Object defaultValue) {
            return new ParameterDef(name, type, description, false, defaultValue, false);
        }
    }
}
