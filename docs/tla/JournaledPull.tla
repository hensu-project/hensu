----------------------------- MODULE JournaledPull -----------------------------
(***************************************************************************)
(* The journaled relay-pull protocol of section 3.1 of                     *)
(* docs/multi-node-execution-protocol.md: claims carry an epoch compared   *)
(* in Postgres, lease expiry uses the DB clock only, every write is fenced *)
(* on the epoch, a tool call exists only as an intent committed under the  *)
(* claim, the relay pulls intents from any node, acknowledges a single-use *)
(* offer token before executing, and completes through any node. One       *)
(* tenant, one relay, one intent per node attempt.                         *)
(*                                                                         *)
(* Node clocks (Skew) are declared to make the point explicit: no guard    *)
(* below reads them.                                                       *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets, TLC

CONSTANTS
    Nodes, Execs, None,
    TTL,              \* lease period, ticks
    RenewEvery,       \* renewal schedule of a healthy node, ticks
    AckDeadline,      \* an offer not acknowledged within this is withdrawn
    CallDeadline,     \* an acknowledged call not completed within this is TIMEOUT
    DispatchDeadline, \* an intent no relay took within this is UNAVAILABLE
    MaxTime, MaxEpoch, MaxTokens,
    Skew,             \* [Nodes -> Int]; unused by design
    Faults            \* subset of {"pause", "partition", "lose", "drain", "abort"}

NULL == -100

VARIABLES
    now, status, dbOk, lastBeat,
    rs,          \* [Execs -> {"new","running","runnable","done"}]
    epoch,       \* [Execs -> Nat]: claim_epoch
    expires,     \* [Execs -> Int]: claim_expires_at
    lastRenew,   \* ghost: DB time of the holder's last renewal
    runs,        \* set of runs [n, x, e, pc]
    hist,        \* ghost [Execs -> Seq(Nat)]: epochs of committed checkpoint writes
    ist,         \* [Execs -> intent state]
    itok,        \* [Execs -> Nat]: current offer token
    ioffExp,     \* [Execs -> Int]: offer expiry
    ideadline,   \* [Execs -> Int]: call deadline once started
    iInsAt,      \* [Execs -> Int]: insert time
    nextTok,
    offers,      \* poll responses in flight: [x, tok]
    relayHas,    \* offers the relay received
    relayAck,    \* offers the relay knows it acknowledged
    executed,    \* offers the relay executed
    completed,   \* offers the relay reported complete
    outcomes,    \* ghost [Execs -> Nat]: outcome writes
    staleInsert, \* ghost: an intent committed by a superseded claim
    liveSteal    \* ghost: a claim superseded a holder that renewed within TTL

vars == <<now, status, dbOk, lastBeat, rs, epoch, expires, lastRenew, runs, hist, ist, itok,
          ioffExp, ideadline, iInsAt, nextTok, offers, relayHas, relayAck, executed,
          completed, outcomes, staleInsert, liveSteal>>

claimVars  == <<rs, epoch, expires, lastRenew>>
intentVars == <<ist, itok, ioffExp, ideadline, iInsAt, nextTok>>
relayVars  == <<offers, relayHas, relayAck, executed, completed>>
envVars    == <<now, status, dbOk, lastBeat>>
ghostVars  == <<hist, outcomes, staleInsert, liveSteal>>

Up(n) == status[n] = "up"
Db(n) == Up(n) /\ dbOk[n]

\* The fence every write carries: WHERE claim_epoch = e AND run_state = 'RUNNING'.
Fenced(r) == rs[r.x] = "running" /\ epoch[r.x] = r.e

Init ==
    /\ now = 0
    /\ status = [n \in Nodes |-> "up"]
    /\ dbOk = [n \in Nodes |-> TRUE]
    /\ lastBeat = [n \in Nodes |-> 0]
    /\ rs = [x \in Execs |-> "new"]
    /\ epoch = [x \in Execs |-> 0]
    /\ expires = [x \in Execs |-> NULL]
    /\ lastRenew = [x \in Execs |-> NULL]
    /\ runs = {}
    /\ hist = [x \in Execs |-> <<>>]
    /\ ist = [x \in Execs |-> "none"]
    /\ itok = [x \in Execs |-> 0]
    /\ ioffExp = [x \in Execs |-> NULL]
    /\ ideadline = [x \in Execs |-> NULL]
    /\ iInsAt = [x \in Execs |-> NULL]
    /\ nextTok = 1
    /\ offers = {}
    /\ relayHas = {}
    /\ relayAck = {}
    /\ executed = {}
    /\ completed = {}
    /\ outcomes = [x \in Execs |-> 0]
    /\ staleInsert = FALSE
    /\ liveSteal = FALSE

----------------------------------------------------------------------------
\* Environment

\* Time advances only while every healthy node has renewed on schedule.
Tick ==
    /\ now < MaxTime
    /\ \A n \in Nodes : Db(n) => (now - lastBeat[n] < RenewEvery)
    /\ now' = now + 1
    /\ UNCHANGED <<status, dbOk, lastBeat, claimVars, runs, intentVars, relayVars, ghostVars>>

Pause(n) == "pause" \in Faults /\ Up(n) /\ status' = [status EXCEPT ![n] = "paused"]
    /\ UNCHANGED <<now, dbOk, lastBeat, claimVars, runs, intentVars, relayVars, ghostVars>>
Unpause(n) == ~Up(n) /\ status' = [status EXCEPT ![n] = "up"]
    /\ UNCHANGED <<now, dbOk, lastBeat, claimVars, runs, intentVars, relayVars, ghostVars>>
Partition(n) == "partition" \in Faults /\ dbOk[n] /\ dbOk' = [dbOk EXCEPT ![n] = FALSE]
    /\ UNCHANGED <<now, status, lastBeat, claimVars, runs, intentVars, relayVars, ghostVars>>
Heal(n) == ~dbOk[n] /\ dbOk' = [dbOk EXCEPT ![n] = TRUE]
    /\ UNCHANGED <<now, status, lastBeat, claimVars, runs, intentVars, relayVars, ghostVars>>

----------------------------------------------------------------------------
\* Claims

\* Batched renewal of this node's live claims; a run whose claim did not
\* renew is cancelled.
Renew(n) ==
    /\ Db(n)
    /\ LET mine == {r \in runs : r.n = n}
           live == {r \in mine : Fenced(r)}
           xs   == {r.x : r \in live}
       IN /\ expires' = [x \in Execs |-> IF x \in xs THEN now + TTL ELSE expires[x]]
          /\ lastRenew' = [x \in Execs |-> IF x \in xs THEN now ELSE lastRenew[x]]
          /\ runs' = runs \ (mine \ live)
    /\ lastBeat' = [lastBeat EXCEPT ![n] = now]
    /\ UNCHANGED <<now, status, dbOk, rs, epoch, hist, intentVars, relayVars, outcomes,
                   staleInsert, liveSteal>>

\* POST /executions on any node.
Start(n, x) ==
    /\ Db(n) /\ rs[x] = "new"
    /\ rs' = [rs EXCEPT ![x] = "running"]
    /\ epoch' = [epoch EXCEPT ![x] = 1]
    /\ expires' = [expires EXCEPT ![x] = now + TTL]
    /\ lastRenew' = [lastRenew EXCEPT ![x] = now]
    /\ runs' = runs \cup {[n |-> n, x |-> x, e |-> 1, pc |-> "checkpoint"]}
    /\ UNCHANGED <<envVars, hist, intentVars, relayVars, outcomes, staleInsert, liveSteal>>

\* Pickup of an expired or released execution: expiry compared with now().
Claim(m, x) ==
    /\ Db(m) /\ epoch[x] < MaxEpoch
    /\ \/ rs[x] = "runnable"
       \/ rs[x] = "running" /\ expires[x] <= now
    /\ liveSteal' = (liveSteal \/ (rs[x] = "running" /\ now - lastRenew[x] < TTL))
    /\ rs' = [rs EXCEPT ![x] = "running"]
    /\ epoch' = [epoch EXCEPT ![x] = epoch[x] + 1]
    /\ expires' = [expires EXCEPT ![x] = now + TTL]
    /\ lastRenew' = [lastRenew EXCEPT ![x] = now]
    /\ runs' = runs \cup {[n |-> m, x |-> x, e |-> epoch[x] + 1, pc |-> "checkpoint"]}
    /\ UNCHANGED <<envVars, hist, intentVars, relayVars, outcomes, staleInsert>>

----------------------------------------------------------------------------
\* The run: checkpoint, one tool call, finish. A run that finds its fence
\* gone stops (RunPreemptedException).

Advance(r, pc) == runs' = (runs \ {r}) \cup {[r EXCEPT !.pc = pc]}

Checkpoint(r) ==
    /\ r \in runs /\ Db(r.n) /\ r.pc = "checkpoint"
    /\ IF Fenced(r)
         THEN /\ hist' = [hist EXCEPT ![r.x] = Append(hist[r.x], r.e)]
              /\ Advance(r, "intent")
         ELSE /\ runs' = runs \ {r}
              /\ UNCHANGED hist
    /\ UNCHANGED <<envVars, claimVars, intentVars, relayVars, outcomes, staleInsert, liveSteal>>

\* INSERT ... SELECT ... WHERE claim_epoch = e ON CONFLICT (key) DO NOTHING:
\* a replay finds the original intent.
InsertIntent(r) ==
    /\ r \in runs /\ Db(r.n) /\ r.pc = "intent"
    /\ IF Fenced(r)
         THEN /\ IF ist[r.x] = "none"
                   THEN /\ ist' = [ist EXCEPT ![r.x] = "pending"]
                        /\ iInsAt' = [iInsAt EXCEPT ![r.x] = now]
                        /\ staleInsert' = (staleInsert \/ r.e # epoch[r.x])
                   ELSE UNCHANGED <<ist, iInsAt, staleInsert>>
              /\ Advance(r, "await")
         ELSE /\ runs' = runs \ {r}
              /\ UNCHANGED <<ist, iInsAt, staleInsert>>
    /\ UNCHANGED <<envVars, claimVars, hist, itok, ioffExp, ideadline, nextTok, relayVars,
                   outcomes, liveSteal>>

\* Consumes the committed outcome; reading needs no fence.
Await(r) ==
    /\ r \in runs /\ Db(r.n) /\ r.pc = "await" /\ ist[r.x] = "done"
    /\ Advance(r, "finish")
    /\ UNCHANGED <<envVars, claimVars, hist, intentVars, relayVars, outcomes, staleInsert,
                   liveSteal>>

\* Terminal write, fenced; unsettled intents are cancelled with it.
Settle(x) == IF ist[x] \in {"pending", "offered"} THEN "cancelled" ELSE ist[x]

Finish(r) ==
    /\ r \in runs /\ Db(r.n) /\ r.pc = "finish"
    /\ IF Fenced(r)
         THEN /\ rs' = [rs EXCEPT ![r.x] = "done"]
              /\ hist' = [hist EXCEPT ![r.x] = Append(hist[r.x], r.e)]
              /\ ist' = [ist EXCEPT ![r.x] = Settle(r.x)]
         ELSE UNCHANGED <<rs, hist, ist>>
    /\ runs' = runs \ {r}
    /\ UNCHANGED <<envVars, epoch, expires, lastRenew, itok, ioffExp, ideadline, iInsAt,
                   nextTok, relayVars, outcomes, staleInsert, liveSteal>>

\* The execution fails for a reason of its own before the call settles.
Abort(r) ==
    /\ "abort" \in Faults
    /\ r \in runs /\ Db(r.n) /\ r.pc = "await"
    /\ IF Fenced(r)
         THEN /\ rs' = [rs EXCEPT ![r.x] = "done"]
              /\ hist' = [hist EXCEPT ![r.x] = Append(hist[r.x], r.e)]
              /\ ist' = [ist EXCEPT ![r.x] = Settle(r.x)]
         ELSE UNCHANGED <<rs, hist, ist>>
    /\ runs' = runs \ {r}
    /\ UNCHANGED <<envVars, epoch, expires, lastRenew, itok, ioffExp, ideadline, iInsAt,
                   nextTok, relayVars, outcomes, staleInsert, liveSteal>>

\* SIGTERM: hand the execution to the cluster.
Drain(r) ==
    /\ "drain" \in Faults
    /\ r \in runs /\ Db(r.n) /\ r.pc \in {"checkpoint", "intent", "await"}
    /\ IF Fenced(r)
         THEN rs' = [rs EXCEPT ![r.x] = "runnable"]
         ELSE UNCHANGED rs
    /\ runs' = runs \ {r}
    /\ UNCHANGED <<envVars, epoch, expires, lastRenew, hist, intentVars, relayVars, outcomes,
                   staleInsert, liveSteal>>

----------------------------------------------------------------------------
\* Intent delivery. Node-side steps run on whichever node serves a poll or
\* a sweep; relay requests reach any healthy node through the LB.

Offer(n, x) ==
    /\ Db(n) /\ ist[x] = "pending" /\ nextTok <= MaxTokens
    /\ ist' = [ist EXCEPT ![x] = "offered"]
    /\ itok' = [itok EXCEPT ![x] = nextTok]
    /\ ioffExp' = [ioffExp EXCEPT ![x] = now + AckDeadline]
    /\ nextTok' = nextTok + 1
    /\ offers' = offers \cup {[x |-> x, tok |-> nextTok]}
    /\ UNCHANGED <<envVars, claimVars, runs, hist, ideadline, iInsAt, relayHas, relayAck,
                   executed, completed, outcomes, staleInsert, liveSteal>>

Withdraw(n, x) ==
    /\ Db(n) /\ ist[x] = "offered" /\ now >= ioffExp[x]
    /\ ist' = [ist EXCEPT ![x] = "pending"]
    /\ UNCHANGED <<envVars, claimVars, runs, hist, itok, ioffExp, ideadline, iInsAt, nextTok,
                   relayVars, outcomes, staleInsert, liveSteal>>

Unavailable(n, x) ==
    /\ Db(n) /\ ist[x] = "pending" /\ now >= iInsAt[x] + DispatchDeadline
    /\ ist' = [ist EXCEPT ![x] = "done"]
    /\ outcomes' = [outcomes EXCEPT ![x] = outcomes[x] + 1]
    /\ UNCHANGED <<envVars, claimVars, runs, hist, itok, ioffExp, ideadline, iInsAt, nextTok,
                   relayVars, staleInsert, liveSteal>>

CallTimeout(n, x) ==
    /\ Db(n) /\ ist[x] = "started" /\ now >= ideadline[x]
    /\ ist' = [ist EXCEPT ![x] = "done"]
    /\ outcomes' = [outcomes EXCEPT ![x] = outcomes[x] + 1]
    /\ UNCHANGED <<envVars, claimVars, runs, hist, itok, ioffExp, ideadline, iInsAt, nextTok,
                   relayVars, staleInsert, liveSteal>>

DeliverOffer(o) ==
    /\ o \in offers
    /\ offers' = offers \ {o}
    /\ relayHas' = relayHas \cup {o}
    /\ UNCHANGED <<envVars, claimVars, runs, hist, intentVars, relayAck, executed, completed,
                   outcomes, staleInsert, liveSteal>>

LoseOffer(o) ==
    /\ "lose" \in Faults /\ o \in offers
    /\ offers' = offers \ {o}
    /\ UNCHANGED <<envVars, claimVars, runs, hist, intentVars, relayHas, relayAck, executed,
                   completed, outcomes, staleInsert, liveSteal>>

\* POST /tasks/{token}/ack. Succeeds only for the current, unexpired offer
\* of the token, or again for the same token once started (a retry after a
\* lost reply). replied = FALSE models the reply being lost.
AckOk(o) ==
    \/ ist[o.x] = "offered" /\ itok[o.x] = o.tok /\ now < ioffExp[o.x]
    \/ ist[o.x] = "started" /\ itok[o.x] = o.tok

Ack(n, o, replied) ==
    /\ Db(n) /\ o \in relayHas /\ o \notin relayAck
    /\ replied \/ "lose" \in Faults
    /\ IF AckOk(o)
         THEN /\ IF ist[o.x] = "offered"
                   THEN /\ ist' = [ist EXCEPT ![o.x] = "started"]
                        /\ ideadline' = [ideadline EXCEPT ![o.x] = now + CallDeadline]
                   ELSE UNCHANGED <<ist, ideadline>>
              /\ IF replied THEN relayAck' = relayAck \cup {o} ELSE UNCHANGED relayAck
              /\ UNCHANGED relayHas
         ELSE /\ IF replied THEN relayHas' = relayHas \ {o} ELSE UNCHANGED relayHas
              /\ UNCHANGED <<ist, ideadline, relayAck>>
    /\ UNCHANGED <<envVars, claimVars, runs, hist, itok, ioffExp, iInsAt, nextTok, offers,
                   executed, completed, outcomes, staleInsert, liveSteal>>

\* The relay executes only what it acknowledged, once per token.
RelayExecute(o) ==
    /\ o \in relayAck /\ o \notin executed
    /\ executed' = executed \cup {o}
    /\ UNCHANGED <<envVars, claimVars, runs, hist, intentVars, offers, relayHas, relayAck,
                   completed, outcomes, staleInsert, liveSteal>>

\* POST /tasks/{token}/complete through any node: first outcome wins.
Complete(n, o) ==
    /\ Db(n) /\ o \in executed /\ o \notin completed
    /\ completed' = completed \cup {o}
    /\ IF ist[o.x] = "started" /\ itok[o.x] = o.tok
         THEN /\ ist' = [ist EXCEPT ![o.x] = "done"]
              /\ outcomes' = [outcomes EXCEPT ![o.x] = outcomes[o.x] + 1]
         ELSE UNCHANGED <<ist, outcomes>>
    /\ UNCHANGED <<envVars, claimVars, runs, hist, itok, ioffExp, ideadline, iInsAt, nextTok,
                   offers, relayHas, relayAck, executed, staleInsert, liveSteal>>

----------------------------------------------------------------------------
Next ==
    \/ Tick
    \/ \E n \in Nodes : Pause(n) \/ Unpause(n) \/ Partition(n) \/ Heal(n) \/ Renew(n)
    \/ \E n \in Nodes, x \in Execs :
           Start(n, x) \/ Claim(n, x) \/ Offer(n, x) \/ Withdraw(n, x)
           \/ Unavailable(n, x) \/ CallTimeout(n, x)
    \/ \E r \in runs : Checkpoint(r) \/ InsertIntent(r) \/ Await(r) \/ Finish(r)
                       \/ Abort(r) \/ Drain(r)
    \/ \E o \in offers : DeliverOffer(o) \/ LoseOffer(o)
    \/ \E o \in relayHas, n \in Nodes, replied \in BOOLEAN : Ack(n, o, replied)
    \/ \E o \in relayAck : RelayExecute(o)
    \/ \E o \in executed, n \in Nodes : Complete(n, o)

Spec == Init /\ [][Next]_vars

----------------------------------------------------------------------------
\* Invariants (section 1)

TypeOK ==
    /\ now \in 0..MaxTime
    /\ rs \in [Execs -> {"new", "running", "runnable", "done"}]
    /\ ist \in [Execs -> {"none", "pending", "offered", "started", "done", "cancelled"}]

S1_OneRunPerClaim ==
    \A x \in Execs : Cardinality({r \in runs : r.x = x /\ r.e = epoch[x]}) <= 1

\* S2: the durable history is one chain of claims – committed writes never
\* go back to an older epoch, and none is newer than the current claim.
S2_FencedHistory ==
    \A x \in Execs :
        \A i \in 1..Len(hist[x]) :
            /\ hist[x][i] <= epoch[x]
            /\ i > 1 => hist[x][i - 1] <= hist[x][i]

S3_NoStaleIntent == ~staleInsert

\* S3a: an intent runs at most once, across offers, nodes and claims.
S3a_AtMostOnce == \A x \in Execs : Cardinality({o \in executed : o.x = x}) <= 1

\* S3: the relay executes only intents that exist.
S3_ExecutedWereJournaled == \A o \in executed : ist[o.x] # "none"

S5_NoLiveSteal == ~liveSteal

S6_OneOutcome == \A x \in Execs : outcomes[x] <= 1
================================================================================
