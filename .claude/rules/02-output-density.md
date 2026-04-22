# 02-output-density.md — Chat Density Protocol

Applies ONLY to assistant chat replies in this session. Artifacts read outside the chat window require normal, grammatical prose.

## In scope (dense mode)
- Assistant chat messages, status lines, end-of-turn summaries.
- Clarifying questions to the user.
- Inline tool-use narration.

## Out of scope (normal prose — full sentences, articles, standard grammar)
Switch to normal prose for these artifacts, then return to dense mode for chat:
- Commit messages, PR titles, PR bodies, release notes.
- Javadoc, KDoc, code comments (see `javadoc` skill).
- Markdown docs under `docs/`, `README.md`, `AGENTS.md`, rule files.
- Slash-command authored output intended for others (issue bodies, reviews).
- Error messages, log strings, user-facing product copy.
- Code identifiers, string literals, test descriptions.

## Dense-mode rules (chat only)
- No pleasantries ("Sure!", "Great question", "Happy to help").
- No hedging without evidence.
- No recapping user inputs or past actions.
- No trailing summaries unless requested (end-of-turn = ≤1 sentence).
- Use imperative mood for actions.
- Use bulleted lists for ≥3 parallel items.
- Drop articles (a/an/the) in bullets, status lines, and headings if clarity is preserved. Keep them in full sentences.
- No filler transitions ("Now,", "So,", "Basically,").
- Limit tool-use meta-narration to ≤1 intent sentence.
- Use arrow notation (`X → Y`) for causal chains.
- For ambiguous intent: ask one short question, no preamble.

### Example

Verbose (reject):
> Great question! I took a look at the file, and it seems that the issue is basically that a new object reference is being created on every render. When you pass an inline object as a prop, it creates a new reference, which then causes the child component to re-render. So, you'll want to wrap it in `useMemo` to fix this.

Dense (accept):
> Inline obj prop → new ref each render → child re-renders. Wrap in `useMemo`.

## Drop dense mode for
Revert to full prose when terseness risks harm or ambiguity:
- Security warnings and threat-model explanations.
- Confirmation prompts before irreversible or destructive actions (force push, `rm -rf`, dropping tables, deleting branches).
- Multi-step sequences where fragment ordering or dropped articles could change the meaning or lead to misexecution.
- When the user explicitly asks for clarification of something already stated tersely.

## Override
Honor per-turn verbose requests ("explain in detail", "walk me through"); revert next turn.
