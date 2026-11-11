package io.hensu.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// The caller's handle on one `tools/call`: a way to stop it, and the `_meta`
/// it carries.
///
/// A budget is the only end a call had before, and a caller that learns the
/// answer is no longer wanted – its execution paused, finished or was timed out
/// elsewhere – had to wait that budget out while the server kept working.
/// {@link #cancel()} ends the call now, by the same means a spent budget does:
/// closing the response stream in the 2026-07-28 era, `notifications/cancelled`
/// in the 2024-11-05 era and over stdio.
///
/// ### Caller `_meta`
/// {@link #meta()} is merged into the request's `_meta` beside the keys the era
/// itself writes. The `io.modelcontextprotocol/` namespace is the protocol's
/// own, and a caller key in it is refused when the control is built rather than
/// silently overwritten on the wire.
///
/// ### Contracts
/// - **Postcondition**: {@link #cancel()} is idempotent and safe from any thread;
///   a call cancelled before it is sent is not sent
/// - **Invariant**: the transport's cancel action runs at most once per send,
///   however {@link #cancel()} races the send
///
/// @implNote **Mutable.** One instance per call; the cancelled flag and the
///     bound action are atomics, so the caller may cancel from any Virtual
///     Thread while the call runs on another. {@link #none()} is a shared inert
///     instance that cannot be cancelled.
/// @see McpConnection#callTool for where a control is honoured
public final class CallControl {

    /// The `_meta` namespace the protocol reserves for itself.
    public static final String RESERVED_META_PREFIX = "io.modelcontextprotocol/";

    private static final CallControl NONE = new CallControl(Map.of(), true);

    private final Map<String, Object> meta;
    private final boolean inert;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Runnable> bound = new AtomicReference<>();

    private CallControl(Map<String, Object> meta, boolean inert) {
        this.meta = meta;
        this.inert = inert;
    }

    /// Returns the control for a caller that never cancels and adds no `_meta`.
    ///
    /// @return the shared inert control, never null
    public static CallControl none() {
        return NONE;
    }

    /// Creates a cancellable control carrying no `_meta`.
    ///
    /// @return a fresh control, never null
    public static CallControl create() {
        return new CallControl(Map.of(), false);
    }

    /// Creates a cancellable control carrying caller `_meta`.
    ///
    /// @param meta the entries to merge into the request's `_meta`, not null;
    ///     copied in iteration order
    /// @return a fresh control, never null
    /// @throws IllegalArgumentException if a key lies in {@link #RESERVED_META_PREFIX}
    /// @throws NullPointerException if meta is null
    public static CallControl withMeta(Map<String, Object> meta) {
        Objects.requireNonNull(meta, "meta must not be null");
        for (String key : meta.keySet()) {
            if (key.startsWith(RESERVED_META_PREFIX)) {
                throw new IllegalArgumentException(
                        "_meta key '"
                                + key
                                + "' lies in the "
                                + RESERVED_META_PREFIX
                                + " namespace, which the protocol reserves for the transport");
            }
        }
        return new CallControl(Collections.unmodifiableMap(new LinkedHashMap<>(meta)), false);
    }

    /// Stops the call: an unsent call is never sent, a running one is abandoned.
    ///
    /// @apiNote **Side effects**: runs the transport's cancel action on the
    ///     calling thread – closing a response stream, or dispatching
    ///     `notifications/cancelled` without waiting for it. Does nothing on
    ///     {@link #none()}.
    public void cancel() {
        if (inert || !cancelled.compareAndSet(false, true)) {
            return;
        }
        Runnable action = bound.getAndSet(null);
        if (action != null) {
            action.run();
        }
    }

    /// Returns whether {@link #cancel()} has been called.
    ///
    /// @return true once cancelled; always false for {@link #none()}
    public boolean isCancelled() {
        return cancelled.get();
    }

    /// Returns the caller's `_meta` entries.
    ///
    /// @return the entries in insertion order, never null (may be empty)
    public Map<String, Object> meta() {
        return meta;
    }

    /// Attaches the cancel action of the send now in flight.
    ///
    /// Replaces whatever an earlier send of the same call attached. When the
    /// control is already cancelled the action runs here instead, so a cancel
    /// that lands between the caller's check and the send still stops it.
    ///
    /// @param action what stops the send, idempotent, not null
    void bind(Runnable action) {
        if (inert) {
            return;
        }
        bound.set(action);
        if (cancelled.get() && bound.compareAndSet(action, null)) {
            action.run();
        }
    }

    /// Detaches an action once its send has ended.
    ///
    /// @param action the action {@link #bind} attached, not null
    void unbind(Runnable action) {
        bound.compareAndSet(action, null);
    }

    /// Names the state, never the `_meta` values.
    ///
    /// @return a description, never null
    @Override
    public String toString() {
        return inert
                ? "CallControl[none]"
                : "CallControl[cancelled=" + cancelled.get() + ", meta=" + meta.keySet() + "]";
    }
}
