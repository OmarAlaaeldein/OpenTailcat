# Security Policy

## 1. Supported Versions

Security updates and patches are applied to the latest release on the `main` branch.

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

---

## 2. Cryptographic & Architecture Standards

Tailcat enforces the following security and cryptographic standards:

* **Cryptographic Primitives:** Standard WireGuard protocol using ChaCha20-Poly1305 for authenticated encryption, Curve25519 (X25519) for ECDH key exchange, and BLAKE2s for hashing.
* **Token Encoding:** Base64URL-encoded CBOR structs with strict 32-byte public key validation.
* **Keystore Protection:** Persistent secrets (tokens and private keys) are encrypted using Android `EncryptedSharedPreferences` with AES-256 GCM backed by the hardware Android Keystore.
* **DNS Leak Prevention:** All DNS queries on ports `53` and `853` (DoT) are intercepted and forced into the encrypted tunnel interface (`tun0`).
* **Kill-Switch:** When enabled, non-VPN network routes are blocked in the event of an unexpected tunnel drop.

---

## 3. Reporting a Vulnerability

We take the security and privacy of Tailcat users very seriously. If you discover a security vulnerability or cryptographic flaw:

1. **Do NOT disclose the issue publicly** in an open GitHub issue or public forum.
2. Please submit a detailed report describing the vulnerability, proof of concept (if applicable), and affected versions.
3. You will receive an acknowledgment within 48 hours and regular updates on the patch progress.

---

## 4. Responsible Disclosure

We kindly ask reporters to adhere to responsible disclosure principles:
* Allow reasonable time for investigation and patch deployment before making any public disclosure.
* Avoid accessing or modifying data that does not belong to you during testing.
