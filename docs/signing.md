# Signing and key migration

Release 1.5.6 is development-signed. `assembleGithubDevelopment` uses the
existing Android development certificate; adding a digest to the updater's
allowlist does not change APK signing.

The updater accepts these SHA-256 certificate digests:

| Certificate | Digest |
| --- | --- |
| Existing development key | `dd767011a3d1223c6145cec024dccb02730e73607fc39544b2fac93adeafa8d6` |
| Future release key | `e22881801806e9809b325e40746536098add8a5902fb4c9bc9134474fa7f69d7` |

The release keystore was generated outside the repository, in an owner-only
directory. The key alias is `opentailcat`; its password lives in a separate
mode-600 file beside it. Back both up in protected storage. Never commit them,
ship them in release assets, or print the password.

## Switching keys

1. Finish the work in [AGENTS.md](../AGENTS.md#remaining-work), including
   physical capture, and keep development and production artifacts distinct.
2. Verify the backup recovers the new key and that its certificate matches the
   pin above.
3. Create a signing lineage from the existing key with `apksigner rotate`.
   A pin alone does not prove an APK can upgrade an installed app.
4. Test an installed development build upgrading through the in-app updater on
   API 26/27 and current Android, including data retention and rejected
   untrusted APKs. API 26/27 cannot use v3 rotation; do not claim those devices
   migrated just because newer Android did.
5. Verify artifacts with `apksigner verify --verbose --print-certs` and the
   package manager, then retire the development pin.

Reference: [apksigner](https://developer.android.com/tools/apksigner) and the
[v3 signing specification](https://source.android.com/docs/security/features/apksigning/v3).
