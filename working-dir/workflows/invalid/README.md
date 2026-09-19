# Invalid fixtures

Every workflow here is **expected to fail**. They are not examples, and they are not broken files
somebody forgot to fix: each one declares something the DSL or the load-time validator must refuse,
and the test is that the refusal happens and says why.

They are named on the command line by path, because they live in a subdirectory:

```bash
hensu build invalid/invalid-capability-gap-writes -d working-dir
```

A build that **succeeds** here is the regression. So is a build that fails with a message naming
something other than the declaration under test — these fixtures check the wording as well as the
refusal, because an error that does not name the key an operator must remove is an error they
cannot act on.

The positive half of the same checks — workflows that must build — lives in
[`../regression/`](../regression/README.md).
