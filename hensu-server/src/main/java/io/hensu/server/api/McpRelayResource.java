package io.hensu.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hensu.core.util.LogSanitizer;
import io.hensu.server.execution.RunRegistry;
import io.hensu.server.mcp.ClusterSignals;
import io.hensu.server.mcp.McpCatalogs;
import io.hensu.server.mcp.ToolIntents;
import io.hensu.server.mcp.ToolIntents.Offer;
import io.hensu.server.security.RequestTenantResolver;
import io.hensu.server.validation.ValidMessage;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// The relay protocol: tenants' relays pull tool calls from any node.
///
/// Every request is an ordinary, short-lived HTTP request the load balancer may send to
/// any node; no node holds a connection to a relay, and every piece of protocol state is
/// a row. A relay:
///
/// 1. publishes its catalog – `PUT /mcp/v1/catalog` `{version, tools}`;
/// 2. long-polls for work – `POST /mcp/v1/poll` `{catalogVersion, max, waitMs}`, answered
///    with offers `{tasks: [{token, key, tool, arguments, deadlineMs}]}`, possibly empty;
/// 3. acknowledges each offer before executing it – `POST /mcp/v1/tasks/{token}/ack`:
///    `204` means execute, `409` means do not;
/// 4. completes it – `POST /mcp/v1/tasks/{token}/complete` `{result}` or `{error}`:
///    `204` recorded, `409` already settled, `404` unknown.
///
/// Exactly one acknowledgement succeeds per intent, so a relay that follows step 3
/// executes each call at most once however often it is offered. `ack` and `complete` are
/// safe to retry.
///
/// ### Tenant Binding
/// The tenant is the authenticated `tenant_id` claim, never a request field: a relay
/// sees, acknowledges and completes only its own tenant's intents, and a token of another
/// tenant is answered exactly as an unknown one.
///
/// ### Validation
/// Every body is checked at the boundary with {@link ValidMessage} and read as a JSON tree,
/// because catalog tools and call outcomes are opaque MCP JSON stored as received. A token's
/// format is checked in the methods, not with a constraint: the protocol answers a malformed
/// token exactly as an unknown one – `409` on `ack`, `404` on `complete` – and a relay acts
/// on those answers, while a constraint violation would answer `400`.
@Path("/mcp/v1")
@Produces(MediaType.APPLICATION_JSON)
public class McpRelayResource {

    private static final Logger LOG = Logger.getLogger(McpRelayResource.class);
    private static final Pattern TOKEN =
            Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final int MAX_BATCH = 32;

    /// Room for `{catalogVersion, max, waitMs}` with a version of the longest allowed length.
    private static final int MAX_POLL_BYTES = 4096;

    private final ToolIntents intents;
    private final McpCatalogs catalogs;
    private final ClusterSignals signals;
    private final RunRegistry registry;
    private final RequestTenantResolver tenantResolver;
    private final ObjectMapper mapper;
    private final Duration pollWait;

    @Inject
    public McpRelayResource(
            ToolIntents intents,
            McpCatalogs catalogs,
            ClusterSignals signals,
            RunRegistry registry,
            RequestTenantResolver tenantResolver,
            ObjectMapper mapper,
            @ConfigProperty(name = "hensu.mcp.poll-wait", defaultValue = "25s") Duration pollWait) {
        this.intents = intents;
        this.catalogs = catalogs;
        this.signals = signals;
        this.registry = registry;
        this.tenantResolver = tenantResolver;
        this.mapper = mapper;
        this.pollWait = pollWait;
    }

    /// Stores the caller's tool catalog under its version.
    @PUT
    @Path("/catalog")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response publishCatalog(@ValidMessage String body) {
        String tenantId = tenantResolver.tenantId();
        JsonNode catalog = read(body);
        String version = catalog.path("version").asText("");
        JsonNode tools = catalog.path("tools");
        if (version.isBlank() || version.length() > 128 || !tools.isArray()) {
            throw new BadRequestException("A catalog needs a version and a tools array");
        }
        catalogs.publish(tenantId, new McpCatalogs.Catalog(version, tools));
        LOG.infov(
                "Catalog {0} of tenant {1} published with {2} tools",
                LogSanitizer.sanitize(version), tenantId, tools.size());
        return Response.noContent().build();
    }

    /// Offers the caller's pending tool calls, waiting up to `waitMs` for one to arrive.
    @POST
    @Path("/poll")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response poll(@ValidMessage(maxBytes = MAX_POLL_BYTES, allowBlank = true) String body) {
        String tenantId = tenantResolver.tenantId();
        JsonNode request = body == null || body.isBlank() ? mapper.createObjectNode() : read(body);
        if (request.hasNonNull("catalogVersion")) {
            Optional<McpCatalogs.Catalog> current = catalogs.catalog(tenantId);
            String asked = request.path("catalogVersion").asText();
            if (current.isEmpty() || !current.get().version().equals(asked)) {
                throw new ClientErrorException(
                        "Catalog version " + asked + " is not the published one; publish it",
                        Response.Status.CONFLICT);
            }
        }
        int max = Math.clamp(request.path("max").asInt(8), 1, MAX_BATCH);
        long waitMillis =
                Math.clamp(
                        request.path("waitMs").asLong(pollWait.toMillis()), 0, pollWait.toMillis());
        long deadline = System.nanoTime() + waitMillis * 1_000_000;

        while (true) {
            ClusterSignals.Waiter waiter = signals.intentWaiter(tenantId);
            List<Offer> offers = intents.offer(tenantId, max);
            long remaining = deadline - System.nanoTime();
            if (!offers.isEmpty() || remaining <= 0 || registry.isDraining()) {
                return Response.ok(tasks(offers)).build();
            }
            try {
                waiter.await(Duration.ofNanos(Math.min(remaining, 1_000_000_000L)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Response.ok(tasks(List.of())).build();
            }
        }
    }

    /// Acknowledges an offer: `204` – execute it; `409` – do not.
    @POST
    @Path("/tasks/{token}/ack")
    @RunOnVirtualThread
    public Response ack(@PathParam("token") String token) {
        String tenantId = tenantResolver.tenantId();
        if (!TOKEN.matcher(token).matches()) {
            throw notOffered();
        }
        return switch (intents.ack(tenantId, token)) {
            case STARTED -> Response.noContent().build();
            case NOT_OFFERED -> throw notOffered();
        };
    }

    /// Records the outcome of an acknowledged call: `{result}` or `{error}`.
    @POST
    @Path("/tasks/{token}/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response complete(@PathParam("token") String token, @ValidMessage String body) {
        String tenantId = tenantResolver.tenantId();
        if (!TOKEN.matcher(token).matches()) {
            throw new NotFoundException("No started call carries this token");
        }
        JsonNode outcome = read(body);
        String status;
        if (outcome.has("result") && outcome.get("result").isObject()) {
            status =
                    outcome.path("result").path("isError").asBoolean(false) ? "FAILURE" : "SUCCESS";
        } else if (outcome.has("error") && outcome.get("error").isObject()) {
            status = "FAILURE";
        } else {
            throw new BadRequestException("A completion carries a result or an error object");
        }
        return switch (intents.complete(
                tenantId, token, new ToolIntents.Settlement(status, outcome.toString()))) {
            case SETTLED -> Response.noContent().build();
            case ALREADY_SETTLED ->
                    throw new ClientErrorException(
                            "The call already settled; this outcome was not recorded",
                            Response.Status.CONFLICT);
            case UNKNOWN -> throw new NotFoundException("No started call carries this token");
        };
    }

    private ObjectNode tasks(List<Offer> offers) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode tasks = response.putArray("tasks");
        for (Offer offer : offers) {
            ObjectNode task = tasks.addObject();
            task.put("token", offer.token());
            task.put("key", offer.key());
            task.put("tool", offer.toolName());
            task.set("arguments", mapper.valueToTree(offer.arguments()));
            task.put("deadlineMs", offer.deadlineMillis());
        }
        return response;
    }

    private JsonNode read(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new BadRequestException("Expected a JSON object");
            }
            return node;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Malformed JSON");
        }
    }

    private static ClientErrorException notOffered() {
        return new ClientErrorException(
                "Not a current offer; do not execute this call", Response.Status.CONFLICT);
    }
}
