# CLI Tool Execution Security Model

This document is for the person who decides what a Hensu **CLI** deployment may touch. It describes
the execution model of the `hensu` command-line runtime — the runtime that owns `commands.yaml`, the
OS sandbox, the built-in file tools, local stdio MCP servers and the interactive reviewer. For each
layer it states what is actually enforced, what is deliberately not, and the decisions an operator
will otherwise get wrong.

**The server does not run this model.** `hensu-server` reaches tools through each calling tenant's
own MCP catalog: there is no command catalog, no Hensu-provided sandbox, no built-in file tools and
no reviewer to ask. The one half the two runtimes genuinely share is the audit trail, because that
is a property of the engine's tool loop rather than of either frontend — the section on it says so
where it applies, and [Named limitations](#named-limitations) says what the server therefore does
not get. Every other guarantee below is a CLI guarantee, and reading it as a description of a server
deployment will overstate what that deployment enforces.

The threat it addresses is specific. A language model selects the tools and supplies the
arguments, and a language model has no reliable judgment about what is harmful. Every layer below
is therefore built so that danger is either inexpressible or physically contained. None of them
inspects a string for malicious intent, because denylists and metacharacter scanners lose to
encodings, indirection and interpreter semantics — and every miss fails open.

## Table of contents

1. [The layers, and what each one guarantees](#the-layers-and-what-each-one-guarantees)
2. [No shell between the agent and the process](#no-shell-between-the-agent-and-the-process)
3. [The catalog is the allowlist](#the-catalog-is-the-allowlist)
4. [OS-enforced containment](#os-enforced-containment)
5. [Built-in file tools](#built-in-file-tools)
6. [Attended and unattended runs](#attended-and-unattended-runs)
7. [Capability gaps](#capability-gaps)
8. [The audit trail](#the-audit-trail)
9. [Decisions an operator has to make](#decisions-an-operator-has-to-make)
10. [Named limitations](#named-limitations)

---

## The layers, and what each one guarantees

| Layer               | Mechanism                                                                  | Guarantees                                                    | Explicitly does not guarantee                                 |
|---------------------|----------------------------------------------------------------------------|---------------------------------------------------------------|---------------------------------------------------------------|
| argv-only execution | `execve` semantics                                                         | no injection, no chaining through arguments                   | nothing about the binary's behaviour                          |
| the shell rung      | hardwired closed policy, loader rejects widening keys                      | agent text cannot reach grants beyond the closed default      | audit granularity at the catalog-ID level                     |
| catalog + schema    | config-load compilation, parameter validation                              | only declared entry points, well-formed arguments             | nothing on paths where the agent authors the executed content |
| OS sandbox          | Linux namespaces and mounts; macOS Seatbelt plus process-group supervision | process-tree containment: filesystem scope, network, lifetime | nothing about whether an in-scope write is wise               |
| built-in file tools | in-process containment on the opened handle, deny list on the config files | working-directory scope, catalog immunity                     | kernel enforcement — this one is a language-level check       |
| profiles and review | human approval, or a typed refusal when no human is present                | judgment where the mechanisms cannot decide                   | —                                                             |
| audit               | `ExecutionListener` plus a durable sink                                    | a complete record of what was attempted and what happened     | prevention                                                    |

Read the table per path, not globally. Wherever an entry's binary executes content the agent can
write — an interpreter running a script, a build tool running a build file, a migration runner
running migrations, a query tool running SQL — the catalog has already granted arbitrary code
execution inside the sandbox. On those paths the catalog governs *which grants* an entry point
carries, and the enforceable boundary is the sandbox. On every other path — deploy, release,
migrate, anything over content the agent did not author — the catalog is a genuine allowlist.

---

## No shell between the agent and the process

Agent-invoked commands never pass through `/bin/sh`, with the single exception of the shell rung,
which does not exist unless an operator declares it and which carries a closed policy with no way
to widen it.

Command templates use the argv-array `exec:` form and are compiled at config-load time. Each
element is either a literal token or a placeholder occupying exactly one argv position. At call
time the agent's arguments bind as whole argv elements, so `&&`, `|`, `;`, `$()`, backticks,
newlines and redirection are inert bytes inside a single argument. Word splitting cannot happen: a
`string` parameter binds to one token and a `list<string>` expands to one token per element.

An author who needs shell features in the fixed part of a command declares `shell: true`. A
shell-mode command accepts agent parameters **only** as environment variables — never spliced into
the command text — so the shell text stays entirely human-authored and the agent's data reaches it
through `getenv`, which no shell re-parses. The mapping is deterministic and collision-checked:
`gradle_args` becomes `HENSU_PARAM_GRADLE_ARGS`, and two parameters mapping to the same variable
name are a config-load error.

---

## The catalog is the allowlist

The agent-visible tool surface is exactly:

- the `tool:` blocks in `commands.yaml`,
- the tools published by servers in `mcp.yaml`,
- the engine's built-in file tools,
- and the shell rung, where an operator declared it.

For every command the executable resolves to an absolute path at load time, so a later `PATH`
change cannot swap the binary. Every parameter is validated against its declared schema before
binding, and an argument that fails validation returns a failure to the agent with no process
spawned.

A node then opts into a subset: `tools = listOf("run-tests", "format")`. The catalog is the
deployment-wide grant; the node list is the per-step one.

---

## OS-enforced containment

Once any interpreter or build tool is in the catalog, the transitive closure of what can execute is
unbounded and unknowable from the argv. The only sound boundary for children, grandchildren and
scripts-calling-scripts is the operating system, so every agent-invoked command launches inside a
sandbox supervisor:

- **Linux** — `bwrap`. The policy compiles to bubblewrap flags; kernel namespaces and bind mounts
  enforce filesystem scope, network isolation and PID-namespace lifetime.
- **macOS** — `sandbox-exec`. The policy compiles to a generated SBPL profile, deny-by-default.
  macOS has no PID namespace, so the runner enforces tree lifetime itself: the command starts in
  its own process group, and on timeout the group is killed and `ProcessHandle.descendants()` is
  swept for escapees.
- **Windows** — no backend. Every sandbox-requiring command takes the degradation path below.

Local stdio MCP servers launch under the same supervisor, with their own `sandbox:` block.

**Degradation is explicit, never silent.** Availability means *working*, not *installed*: at
startup the backend runs a probe that executes a trivial command under its full mechanism. On a
host where that probe fails — `bwrap` absent, unprivileged user namespaces disabled, a
`sandbox-exec` profile refusal, or a platform with no backend at all — a command whose policy
requires a sandbox is **not** run uncontained. It is demoted to approval-required, and in a run
with nobody to approve it, it becomes a capability gap.

A reviewer who approves such a call has approved running it uncontained, once, and the audit
records it that way. The deployment-wide escape hatch remains `-Dtoolexec.allowUnsandboxed=true`,
which logs loudly and should never be set in a deployment.

---

## Built-in file tools

`read_file`, `list_dir`, `glob`, `grep`, `write_file` and `edit_file` are engine capabilities rather
than catalog entries: they need no binary, no sandbox launch and no declaration, and a run that
cannot read the input it is meant to act on is not constrained but non-functional. The CLI is the
only runtime that wires them, and a build-time test keeps them off the server, where a provider
rooted on a multi-tenant host would write where no tenant can see it.

They are also the one capability in this design that the kernel does not contain, which makes them
the highest-value target in the system. Two properties follow:

- **Containment is asserted on the opened handle, not on a resolved string.** Resolving a path,
  checking it, then opening it is a time-of-check-to-time-of-use bug: anything able to write into
  the tree can swap a path component for a symlink in between, and with the shell rung running
  concurrently in the same directory that race is trivially winnable. The provider walks from the
  root one segment at a time with `NOFOLLOW_LINKS`, restarts from the root when a segment is
  itself a link, and re-derives containment from the final opened handle before a byte moves.
- **The catalog stays immune.** `commands.yaml` and `mcp.yaml` are refused to `write_file` and
  `edit_file` unconditionally, by the same deny list the sandbox launchers use to re-mask both
  files read-only. With the rung declared, rewriting the catalog is a grant-escalation primitive:
  an added entry can carry `network: true` and a `cache:` mount.

Every tool caps its own output and truncates with an explicit marker, because an untruncated
`grep` over a large tree is a context-window denial of service against the agent itself.

---

## Attended and unattended runs

The engine has no notion of human presence and does not gain one. Attendedness is a property of
the frontend: the CLI decides it when it chooses whether to install an interactive reviewer, and
that decision becomes one boolean reaching the tool layer.

```bash
hensu run my-workflow --interactive    # a human is at the terminal
hensu run my-workflow --unattended     # nobody is; the default
```

The two flags contradict each other and are refused together. A run that does not ask for a
reviewer does not have one, so `--unattended` is what a plain `hensu run` already means; the flag
exists to say so out loud in a script or a CI job.

Each command carries `unattended: true|false` (default `true`) and `approval: none|required`
(default `none`). They are orthogonal, and the four combinations resolve as:

| Run mode   | Entry declares                       | Outcome                                                              |
|------------|--------------------------------------|----------------------------------------------------------------------|
| attended   | `approval: none`                     | runs                                                                 |
| attended   | `approval: required`                 | routed to the reviewer; approval runs it, rejection returns `DENIED` |
| unattended | `unattended: true`, `approval: none` | runs                                                                 |
| unattended | `unattended: false`                  | capability gap; nothing is launched                                  |
| unattended | `approval: required`                 | capability gap; nothing is launched, whatever `unattended:` says     |

`unattended: false` is not "ask anyway". In a run with no human there is nobody to ask, so the call
is refused with a typed outcome the workflow can route on — never silently skipped, and never
executed on the assumption that somebody will notice.

**A run with no reviewer refuses; it never auto-approves.** A question that could not be asked is
not an approval. The same applies when an attended run's review channel disconnects mid-call.

The reviewer sees the fully resolved argv, one token per line, not the template it came from.
Approving `git push --force origin main` is approving those five tokens; approving `git {args}`
would be approving a shape and trusting the binding, which is the trust the argv-only model exists
to avoid.

The same matrix governs local stdio MCP servers, at the server level, because a long-lived server
process cannot be gated per call. The one decision made at launch rather than per call is whether
to start a server with no containment available, and that too is refused in a run with no reviewer.

Because the server outlives every call it answers, the reviewer asked for it has to be answering
for the whole process rather than for one run. A daemon serving several runs at once therefore
refuses the launch unless exactly one run is in flight and that run is attended: two runs cannot be
told apart at that point, and one unattended run in the set means the server would outlive a run
that never had a reviewer to consent to it.

---

## Capability gaps

A declared tool can also be missing because its **source** never offered it: `mcp.yaml` did not
parse, a server is not installed, or containment was unavailable and nobody could approve starting
it uncontained. That is not a refused call and leaves no gap record, so the CLI prints those reasons
in their own **Tool sources** section beside the gaps. The two answer the same operator question —
why did the run do less than I asked — at different points: a gap is a call that was stopped, a
notice is a tool that was never there.

A refusal is not a failure, and a workflow needs to tell them apart. When a tool call is refused
rather than merely unsuccessful, the tool loop appends a record to the reserved state key
`_capability_gaps` and keeps `_capability_gap_count` in step with it. The keys and the routing they
enable belong to the engine; the gates that produce refusals here, and the summary that reports
them, are the CLI's.

A refusal is `UNKNOWN_TOOL` (the agent asked for something its node does not grant), `DENIED` (a
reviewer said no, or an unattended run had nobody to ask), `SANDBOX_UNAVAILABLE` and
`SANDBOX_REFUSED`. `FAILURE` and `TIMEOUT` are not gaps — the tool ran. `VALIDATION_FAILED` is not
one either: the capability was there and the agent's arguments were wrong. `BUDGET_EXHAUSTED`
describes a loop that ran long, not a capability the deployment lacks.

Each record carries the tool name, the refusing gate, the node that asked, and the argument **key**
names — never their values, so a parameter declared `secret:` cannot leak into state that survives
checkpoint and resume.

A workflow routes on it with an ordinary condition. No new transition type, no decorator:

```kotlin
node("implement") {
    agent = "implementer"
    prompt = "Fix the failing test."

    onCondition("_capability_gap_count") {
        whenValue greaterThanOrEqual 1 goto "escalate"
    }
    onSuccess goto "review"
}
```

The node did its work and reported; the graph decides what that means. Both keys belong to the
engine — a node that declares either in `writes` is a build error, because the agent's own output
would otherwise be able to erase the evidence that it was blocked.

At the end of a run the CLI prints the aggregate, and omits the section entirely when there is
nothing to report:

```
  Capability gaps:
  deploy · DENIED ×2 · asked by implement, retry
  grant these in commands.yaml, in mcp.yaml, or in the node's tools list — or leave them refused on purpose
```

That is the evidence from which an operator grows the catalog or a per-node grant — and, with the
rung declared, the place where a recurring command line reveals itself as an entry somebody should
write down.

---

## The audit trail

This is the one section of this document that is not CLI-only. Every invocation — approved, denied,
validation-failed or sandbox-refused — flows through the `ExecutionListener` hooks with the resolved
argv, the decision, the exit status, the duration and truncated output. The trail is a property of
the engine's tool loop rather than of a frontend, so it is identical on the CLI and the server and
no provider can bypass it.

It also outlives the process:

- **CLI** — one JSON line per settled call appended to `~/.hensu/tool-audit.log` (or
  `$XDG_DATA_HOME/hensu/tool-audit.log`).
- **Server**, for contrast — one row per settled call in `runtime.tool_audit`, indexed by execution,
  carrying the same fields. The listener writing it is composed **unconditionally**. The verbose log
  lines are not: they are enabled by `hensu.verbose.enabled`, which ships `false`, so a default
  deployment writes rows and no log lines. A record a configuration flag can silence is a debugging
  aid, not a record.

Both sinks assemble a row from two events — the arguments arrive with the request, the outcome with
the result — and pair them by the call id the loop mints per call. Pairing on node and tool name
instead would be correct only while no two concurrent calls share both, which is true of today's
graph shapes (parallel branches report as `node/branch`, sub-workflows run sequentially) and is a
property of the graph rather than of the audit. The id makes the row's arguments belong to the call
its outcome describes, whatever the graph grows into.

A write failure never fails the workflow. An audit sink that can abort a run is worse than a gap in
the trail, because the run is the thing with real effects.

Secrets are redacted at the source: a parameter declared `secret:` in `commands.yaml` becomes a
sensitive parameter on the tool definition, and the loop replaces its value before the event is
constructed. Nothing downstream has to remember to redact.

---

## Decisions an operator has to make

**Give a sandboxed build somewhere to write.** By default the filesystem is a read-only mount of
the working directory, which means a build has no writable home and no dependency cache. Declare
both:

```yaml
  run-tests:
    exec: ["./gradlew", "test"]
    sandbox:
      write: ["build/", ".gradle/"]      # inside the working directory
      cache: ["~/.gradle/caches"]        # an absolute host path, deliberately
```

`write:` is confined to the working directory and its entries are created before launch if they do
not exist. `cache:` is operator-authored and absolute by design — a different trust level from
`write:`, and the reason the two are separate keys. Each command also gets a private, writable
`$HOME` for the duration of the call.

**`network: false` cuts host services too.** It is not a firewall against the internet with an
exemption for localhost. A command that talks to a database on the same machine, a local registry
or a package mirror needs `network: true`, and that grant is real: on an entry whose binary
executes agent-authored content, it is an exfiltration path.

**Do not grant `write:` over the catalog's own directory and assume the catalog is safe.** It is
safe, but because the launchers re-mask `commands.yaml` and `mcp.yaml` read-only *after* the
`write:` subtrees and the file tools refuse them outright — not because you scoped the grant well.

**Decide what genuinely needs a human.** A catalog whose entries all declare `unattended: false` is
a catalog no unattended run can use, and the pressure to work around that is where real mistakes
happen. Reserve it for blast radius you would not accept without supervision, and reserve
`approval: required` for the irreversible.

**Do not carry these guarantees to a server deployment.** Nothing above describes how
`hensu-server` reaches tools. See below.

---

## Named limitations

These are properties this model deliberately does not have. They are cheap to state here and
expensive to discover in production.

**The server shares none of this except the audit trail.** The catalog, the sandbox, the file
tools and the gates are all CLI-side: the gates live in the CLI producer's approval decorator, and
the rest is reachable only through providers the CLI wires. Server-side MCP tools run ungated inside the calling
tenant's own catalog — there is no interactive reviewer, and containment is whatever that tenant's
MCP server provides rather than Hensu's sandbox. Because the audit half genuinely is engine-wide, a
server deployment can look from its audit rows as though it enforced the rest. It did not.

**In-flight tool calls are not cancellable.** Pausing or cancelling an execution does not interrupt
a running tool call; it runs to its own deadline and its result is discarded. The per-call timeout
is therefore the only bound on how long a cancelled execution keeps a process alive — a command
with a thirty-minute timeout can outlive the run that started it by that long.

**Network filtering is boolean.** `network:` is on or off. An egress proxy or an allowlist is its
own effort.

**There is no catalog hot-reload and no agent-proposed entries.** Capability gaps plus the run
summary are the feedback loop. A run that can extend its own allowlist has no allowlist.

**One working directory per process.** The catalog, the MCP declarations and the file tools' root
are process-wide. Two concurrent daemon runs rooted at different directories are not a supported
configuration. Run mode is not process-wide: each run registers its own, and the decisions that
belong to no call read the whole set rather than whichever run registered last.

**Windows has no containment backend.** Every sandbox-requiring command degrades to
approval-required there, and the shell rung is unavailable outright, its entire justification being
the policy floor the sandbox enforces.

---

## See also

- [`docs/command-catalog.md`](command-catalog.md) — the grammar, and how to add a command
- [`docs/dsl-reference.md`](dsl-reference.md) — granting tools per node, and routing on gaps
- [`hensu-cli/README.md`](../hensu-cli/README.md) — run modes, the working directory, and the
  completion summary these gates feed
- [`docs/unified-architecture.md`](unified-architecture.md) — where the tool seam sits in the engine,
  and how the server reaches tools instead
- [`docs/developer-guide-server.md`](developer-guide-server.md) — the server's own tool path
