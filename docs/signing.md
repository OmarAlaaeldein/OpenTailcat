# Signing and key migration

Release 1.5.6 is development-signed (`assembleGithubDevelopment` uses the
existing Android development certificate).

Updater-accepted SHA-256 certificate digests:

| Certificate | Digest |
| --- | --- |
| Existing development key | `dd767011a3d1223c6145cec024dccb02730e73607fc39544b2fac93adeafa8d6` |
| Future release key | `e22881801806e9809b325e40746536098add8a5902fb4c9bc9134474fa7f69d7` |

The release keystore lives outside the repo (owner-only dir, alias
`opentailcat`, password in a separate mode-600 file). Back both up in
protected storage. Never commit them or print the password.

## Switching keys

1. Finish [AGENTS.md](../AGENTS.md#remaining-work) first. Keep development
   and production artifacts distinct.
2. Verify the backup recovers the key and its certificate matches the pin.
3. Create a signing lineage with `apksigner rotate` (a pin alone does not
   prove upgradeability).
4. Test updater migration on API 26/27 and current Android, including data
   retention and rejected untrusted APKs. API 26/27 cannot use v3 rotation.
5. Verify with `apksigner verify --verbose --print-certs`, then retire the
   development pin.
