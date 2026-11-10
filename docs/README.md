# Documentation

Start with the [README](../README.md) if you want to know what OpenTailcat is,
what it needs, and how to install it. [AGENTS.md](../AGENTS.md) is the
engineering contract: architecture, safety invariants, build, test, and
release.

| If you want to… | Read |
| --- | --- |
| Understand the product, install it, or check its limits | [README.md](../README.md) |
| Change the app, engine, or build | [AGENTS.md](../AGENTS.md) |
| Know what changed between versions | [releases/](releases/) |
| Cut or sign a release | [signing.md](signing.md), [AGENTS.md](../AGENTS.md#release-process) |
| Understand what data is stored or sent | [PRIVACY_POLICY.md](../PRIVACY_POLICY.md) |
| Report a vulnerability | [SECURITY.md](../SECURITY.md) |
| Run emulator or live-gateway tests | [scripts/emulator/README.md](../scripts/emulator/README.md) |
| Check the pinned upstream Tailcat commit | [third_party/PROVENANCE.md](../third_party/PROVENANCE.md) |

## Release notes

`releases/` is a historical record, one file per version, named after the
version. The newest is [1.5.8](releases/1.5.8.md). Each note describes what
changed, what was verified at the time, and the published artifact hashes.
Verification lines are evidence for that release, not a promise about later
builds.

Do not rewrite an old note when behaviour changes; add a new one. Do not edit a
published note's artifact hashes.

## Keeping these docs useful

- Lead with the audience and the answer; detail comes after.
- Product-facing explanation belongs in the README, engineering detail in
  AGENTS.md, history in `releases/`.
- Never put tokens, live captures, signing secrets, or personal account names
  in documentation.
