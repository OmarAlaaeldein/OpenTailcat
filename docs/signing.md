# Signing and Key Migration

Notes on how OpenTailcat APKs are signed, which certificates the in-app updater accepts, and how to move from the development key to a production release key.

## Current builds

Preview builds (`assembleGithubDevelopment`) are signed with the build machine's Android debug keystore. `UpdatePolicy.kt` pins two accepted signing certificate SHA-256 digests:

| Key | SHA-256 digest |
| --- | --- |
| Development key (active) | `dd767011a3d1223c6145cec024dccb02730e73607fc39544b2fac93adeafa8d6` |
| Future release key (pinned, not yet used) | `e22881801806e9809b325e40746536098add8a5902fb4c9bc9134474fa7f69d7` |

Having the second digest pinned prepares for future rotation, but Android only permits an in-place upgrade to a new key when the APK includes a verified v3 signing lineage from the old key.

Verify an APK's signer with:

```bash
apksigner verify --print-certs <apk>
```

## Building with the release key

Keep the release keystore outside the repository (owner-only directory, alias `opentailcat`, password stored separately with `0600` permissions) and back it up securely. Never commit or print the keystore or passwords.

```bash
export OPENTAILCAT_RELEASE_STORE_FILE=/path/to/keystore.jks
export OPENTAILCAT_RELEASE_STORE_PASSWORD='...'
export OPENTAILCAT_RELEASE_KEY_ALIAS=opentailcat
export OPENTAILCAT_RELEASE_KEY_PASSWORD='...'
./gradlew assembleGithubRelease
```

Without these environment variables set, `assembleGithubRelease` produces unsigned artifacts.

## Rotating to the release key

1. Complete the prerequisites in [AGENTS.md#remaining-work](../AGENTS.md#remaining-work), keeping preview and production artifacts separate.
2. Confirm the backup restores the keystore and matches the pinned digest above.
3. Generate a signing lineage with `apksigner rotate`.
4. Test in-app updater migration on both Android 8–8.1 (API 26/27, which don't support v3 rotation) and current Android versions, checking profile retention and rejection of untrusted APKs.
5. Verify the rotated APK with `apksigner verify --verbose --print-certs`, then remove the development pin once migration is complete.
