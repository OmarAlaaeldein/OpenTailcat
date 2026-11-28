# Documentation

| Topic | File |
| --- | --- |
| Product overview, setup, and limitations | [README.md](../README.md) |
| Architecture, safety invariants, build, test, and open work | [AGENTS.md](../AGENTS.md) |
| Exit-node mode ("Share this phone") engineering details | [AGENTS.md#exit-node](../AGENTS.md#exit-node) |
| Release history and APK checksums | [releases/](releases/) |
| Release signing keys and rotation | [signing.md](signing.md), [AGENTS.md#release-process](../AGENTS.md#release-process) |
| Data storage, network requests, and permissions | [PRIVACY_POLICY.md](../PRIVACY_POLICY.md) |
| Security reporting and controls | [SECURITY.md](../SECURITY.md) |
| Emulator and live-gateway test scripts | [scripts/emulator/README.md](../scripts/emulator/README.md) |
| Pinned upstream Tailcat commit | [third_party/PROVENANCE.md](../third_party/PROVENANCE.md) |
| Tailcat Android integration patch | [core-engine/integration/README.md](../core-engine/integration/README.md) |
| TLA+ lifecycle models | [formal/README.md](../formal/README.md) |

## Release notes

`releases/` keeps one file per published version (latest: [1.8.5](releases/1.8.5.md)), listing changes, what was tested for that build, and SHA-256 checksums. Treat old release notes as immutable: add a new file for each release instead of editing past notes or hashes.

Never commit live tokens, signing secrets, traffic captures, or personal account names to documentation.
