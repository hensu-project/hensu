# Regression fixtures

Workflows here exist to be run, not to be read as examples. Each one pins a behaviour that broke
once, and each carries a header comment naming the defect and the symptom that means it is back.

They are named on the command line by path, because they live in a subdirectory:

```bash
hensu run regression/regression-capability-gap -d working-dir --no-daemon -c '{"subject": "x"}'
```

Every workflow here **must build and run**. The opposite case — a workflow that must fail to build —
lives in [`../invalid/`](../invalid/README.md).

`review-arms-permitted.kt` carries no `regression-` prefix because it is a positive build check
rather than a behaviour regression: it asserts that the review shapes the validator *permits* still
compile. It is a fixture all the same, which is why it is here.

Most are exercised from a manual test plan under [`docs/`](../../../docs), which records the observed
outcome of each run. A fixture whose header says "run the exercise, not the file" means exactly that:
it is meaningful only in the setup its plan describes.
