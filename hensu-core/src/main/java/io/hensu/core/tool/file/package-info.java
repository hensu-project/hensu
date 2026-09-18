/// Built-in file tools: the engine's own reading and editing capability.
///
/// Everything here is pure JDK with no reflection and no classpath scanning, so
/// it is native-image safe by construction even though its only consumer is the
/// JVM-only CLI.
///
/// The package is neutral core code and must stay free of framework
/// dependencies. Its layer rule is narrower than the rest of `hensu-core`,
/// though: it is referenced from `hensu-core` and `hensu-cli`, and never from
/// `hensu-server`. The server executes nothing locally – every side effect leaves
/// as an MCP request to a tenant's own client – so a provider rooted on a
/// multi-tenant host would write where no tenant can see it and where tenants
/// cannot be isolated from one another. `NoLocalFileToolsOnTheServerTest` fails
/// the build if a server source names this package at all, because a producer
/// wiring it would look like ordinary CDI code in review.
///
/// @see io.hensu.core.tool.file.FileToolProvider for the six tools
/// @see io.hensu.core.tool.file.PathGuard for the containment walk they share
package io.hensu.core.tool.file;
