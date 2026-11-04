--------------------------- MODULE MCJournaledPull ---------------------------
EXTENDS JournaledPull
CONSTANTS n1, n2, x1, x2
MCNodes == {n1, n2}
MCExecs == {x1, x2}
MCExecsOne == {x1}
\* n2 four ticks ahead. No guard reads it, by design.
MCSkewAhead == (n1 :> 0) @@ (n2 :> 4)
\* No guard reads Skew, so the two nodes are interchangeable.
MCSymmetry == Permutations(MCNodes)
\* No guard tells two executions apart either; used by the two-execution models.
MCSymmetryAll == Permutations(MCNodes) \cup Permutations(MCExecs)
\* Non-vacuity probes: each must be violated, showing the model reaches the
\* situation the safety invariants are about.
Reach_ZombieRun == ~(\E r \in runs : r.e < epoch[r.x])
Reach_ReofferedIntent == ~(\E o1, o2 \in relayHas : o1.x = o2.x /\ o1.tok # o2.tok)
Reach_ExecutedAfterClaimChange == ~(\E o \in executed : epoch[o.x] >= 2)
Reach_TimeoutOutcome == ~(\E x \in Execs : ist[x] = "done" /\ \E o \in executed : o.x = x /\ o \notin completed)
\* Two-execution probes: the interference the two-execution models add.
\* One node's batched renewal covers a live run and a superseded one.
Reach_MixedRenewal ==
    ~(\E r1, r2 \in runs : r1.n = r2.n /\ r1.x # r2.x /\ Fenced(r1) /\ ~Fenced(r2))
\* The shared token counter interleaves the executions: the relay holds a
\* lapsed and a current offer of one execution, the other's offer between them.
Reach_InterleavedTokens ==
    ~(\E o1, o2, o3 \in relayHas :
        o1.x = o3.x /\ o2.x # o1.x /\ o1.tok < o2.tok /\ o2.tok < o3.tok)
\* A deadline sweep settles one execution's intent, which the relay never ran,
\* while the other execution's call is in flight.
Reach_SweepBesideCall ==
    ~(\E x, y \in Execs :
        /\ x # y /\ ist[x] = "done" /\ ~(\E o \in executed : o.x = x)
        /\ ist[y] = "started")
==========================================================================
