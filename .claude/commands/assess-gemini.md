---
description: Verify Gemini's last opinion by requesting evidence.
---
# Assess Gemini

Target: $ARGUMENTS (or Gemini's last reply).

Token rule: see `01-model-coordination.md` §2. Do NOT `Read` files just to confirm citations — defer verification to the moment you edit that code. Only `Read` if the claim would change the user's decision *before* any edit.

1. **List disputed claims** lacking `path:line`. Skip if all claims already cited concretely.

2. **Query Gemini** (`mcp__gemini__ask-gemini`, `gemini-3.1-flash-lite-preview`) — only if step 1 non-empty:
> Back prior claims with exact `path:line-range` + quoted block. No re-argument, no new claims, no fabrication.

3. **Assess** (one line per disputed claim):
   - `agree    | path:line | quoted evidence`
   - `disagree | path:line | counter-quote`
   - `unclear  | path:line | what's missing`
   - `missed   | path:line | what Gemini ignored`

4. **Recommend** ≤2 sentences. Wait for user.
