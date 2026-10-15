# Build reference: the Android workflow as it was

`build-android-reference.yml` is a byte-for-byte copy of
`.github/workflows/build-android.yml` as it stood before the 15/10/2026 engine-matrix
fix (commit `7cf2a1d`). It is kept as a working reference for the steps,
dependencies, caches, staging paths, version handling, artifact uploads, release
publishing and cleanup that the fixed workflow still performs.

## Why it is not under `.github/workflows/`

GitHub Actions treats **every** `*.yml` / `*.yaml` file directly inside
`.github/workflows/` as workable workflow, regardless of its name. A copy called
`build-android-reference.yml` placed there would not be inert: it would appear in
the Actions tab, be runnable by hand, and — more importantly — its
`on: pull_request` trigger would fire a second, full ~15-minute Android build on
every qualifying PR. That is exactly the duplicate-build cost the 01/08/2026
change removed the `push:` trigger to avoid (see the comment above `on:` in the
live workflow).

Keeping the copy one directory up (here, in `docs/`) means GitHub never reads it as
a workflow, while it still travels with the repository and can be diffed against
the live file:

```bash
diff -u docs/build-reference/build-android-reference.yml .github/workflows/build-android.yml
```

The live workflow is the only one that runs; nothing in this directory is
referenced by it.
