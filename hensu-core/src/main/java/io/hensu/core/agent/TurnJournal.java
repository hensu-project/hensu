package io.hensu.core.agent;

import java.util.function.Supplier;

/// The journal of one tool session, as an agent adapter sees it.
///
/// An adapter routes every step it cannot reproduce – the conversation it opens with,
/// each answer of its model – through {@link #turn}, encoded as a string it can decode
/// again. During a first run the journal records each turn before the session acts on
/// it; during a recovery it hands back what was recorded, so the session rebuilds the
/// original conversation without asking the model again. Encoding is the adapter's
/// business: the journal stores the string as given.
///
/// @see ToolCapable#openToolSession
@FunctionalInterface
public interface TurnJournal {

    /// A journal that records nothing: every turn is produced live.
    TurnJournal NONE = (_, live) -> live.get();

    /// Returns the turn recorded at `index`, or produces it with `live`, records it and
    /// returns it.
    ///
    /// @param index the turn's index within the session, zero-based and dense
    /// @param live produces the encoded turn when none is recorded, not null
    /// @return the encoded turn, never null
    /// @throws io.hensu.core.execution.RunPreemptedException if the turn cannot be recorded
    String turn(int index, Supplier<String> live);
}
