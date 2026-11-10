# Signing and key migration

Operator note for release signing: which key signs previews, which
certificates the in-app updater accepts, and what must happen before production
signing.

## Current builds

Development previews (1.5.8) are signed by `assembleGithubDevelopment` with the
build machine's Android debug key. The updater accepts only APKs whose signing
certificate matches one of these SHA-256 digests:

| Certificate | Digest |
| --- | --- |
| Existing development key | `dd767011a3d1223c6145cec024dccb02730e73607fc39544b2fac93adeafa8d6` |
| Future release key | `e22881801806e9809b325e40746536098add8a5902fb4c9bc9134474fa7f69d7` |

The second digest pins a future release key; current artifacts are not signed
with it. Pinning a digest does not rotate a signature: an installed app only
accepts a new key when the APK carries a verified signing lineage.

Check a built APK with:

```bash
apksigner verify --print-certs <apk>
```

## Building with the release key

The release keystore lives outside the repo (owner-only directory, alias
`opentailcat`, password in a separate mode-600 file). Back it up in protected
storage and never commit or print it. Set the environment variables and build:

```bash
export OPENTAILCAT_RELEASE_STORE_FILE=/path/to/keystore.jks
export OPENTAILCAT_RELEASE_STORE_PASSWORD='...'
export OPENTAILCAT_RELEASE_KEY_ALIAS=opentailcat
export OPENTAILCAT_RELEASE_KEY_PASSWORD='...'
./gradlew assembleGithubRelease
```

Without these variables, release artifacts are unsigned.

## Switching keys

1. Finish [the remaining work](../AGENTS.md#remaining-work) first. Keep
   development and production artifacts distinct.
2. Verify the backup recovers the key and its certificate matches the pin.
3. Create a signing lineage with `apksigner rotate` (a pin alone does not prove
   upgradeability).
4. Test updater migration on API 26/27 and current Android, including data
   retention and rejected untrusted APKs. API 26/27 cannot use v3 rotation.
5. Verify with `apksigner verify --verbose --print-certs`, then retire the
   development pin.

The current development previews continue to use the existing development key
for update compatibility. Production signing and migration remain future work.
