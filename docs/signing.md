# Signing and key migration

Version 1.5.4 is a development build. `assembleGithubDevelopment` uses the
existing Android development certificate. Adding a certificate to the updater's
allowlist does not change APK signing or satisfy the production release gates.

The updater pins these SHA-256 certificate digests:

| Certificate | Digest |
| --- | --- |
| Existing development key | `dd767011a3d1223c6145cec024dccb02730e73607fc39544b2fac93adeafa8d6` |
| Future release key | `e22881801806e9809b325e40746536098add8a5902fb4c9bc9134474fa7f69d7` |

The release keystore was generated outside the repository, on the release
operator's workstation, in an owner-only directory outside any checkout. The
key alias is `opentailcat`; the password lives in a separate owner-only file
beside it, and both files have mode 600. That is local storage, not proof of an
offline backup. Back up both files in protected storage before relying on this
key. Never commit them, include them in release assets, or print the password
in logs.

Before switching signing keys:

1. Pass the production gates in [AGENTS.md](../AGENTS.md), including physical
   packet capture. Keep development and production artifacts distinct.
2. Verify that the protected backup can recover the new key and that its
   certificate matches the pin above.
3. Create and verify a signing lineage from the existing key to the release
   key with the SDK's `apksigner rotate` command. A pin alone does not prove
   that an APK can upgrade an installed app.
4. Choose and test the minimum API for rotation. Android's signing tool can
   retain the old key on older platforms; API 26/27 cannot use v3 rotation.
   Do not claim those devices migrated solely because newer Android did.
5. Test an installed development build upgrading through the in-app updater
   on API 26/27 and current Android, including data retention and rejected
   untrusted APKs. Verify each artifact with `apksigner verify --verbose
   --print-certs` and Android's package manager.
6. Retire the development pin only after the supported migration policy and
   compatibility tests permit it.

Reference: Android's [apksigner documentation](https://developer.android.com/tools/apksigner)
and [v3 signing specification](https://source.android.com/docs/security/features/apksigning/v3).
