# Signing & Publishing

How to fill the four `providers.environmentVariable(...)` slots in `build.gradle.kts` so `signPlugin` and `publishPlugin` work — locally and in CI.

| Env var                | Used by          | Source                                                  |
|------------------------|------------------|---------------------------------------------------------|
| `CERTIFICATE_CHAIN`    | `signPlugin`     | Generated locally (PEM, public cert)                    |
| `PRIVATE_KEY`          | `signPlugin`     | Generated locally (PEM, encrypted RSA private key)      |
| `PRIVATE_KEY_PASSWORD` | `signPlugin`     | The password you set when generating the private key    |
| `PUBLISH_TOKEN`        | `publishPlugin`  | JetBrains Marketplace personal token                    |

Without these set, `signPlugin` / `publishPlugin` will fail. **`buildPlugin` itself does not require them** — daily development is unaffected.

Authoritative reference: <https://plugins.jetbrains.com/docs/intellij/plugin-signing.html>. If anything below disagrees with that page, the page wins.

---

## 1. Generate cert chain + private key (one-time)

The JetBrains plugin signer expects an RSA keypair where the public side is wrapped in a self-signed X.509 certificate (the "chain").

```bash
# Pick any password — you'll need it as PRIVATE_KEY_PASSWORD later
PASS='change-me-now'

# 1. Encrypted RSA private key (4096-bit)
openssl genpkey \
  -aes-256-cbc -pass "pass:$PASS" \
  -algorithm RSA -pkeyopt rsa_keygen_bits:4096 \
  -out private_encrypted.pem

# 2. Self-signed certificate from that key (10-year validity)
openssl req -new -x509 -days 3650 \
  -key private_encrypted.pem -passin "pass:$PASS" \
  -subj "/CN=Toni Masotti/O=tonihacks/C=IT" \
  -out chain.crt
```

Outputs:

- `private_encrypted.pem` — the private key (keep secret, never commit)
- `chain.crt` — the public certificate chain (also secret per JetBrains best practice; do not publish)

Store both files **outside the repo** in a password manager / secret store. They survive across plugin versions — generate once, reuse forever.

---

## 2. Get a Marketplace publish token (one-time)

1. Log in at <https://plugins.jetbrains.com/>
2. Profile → **My Tokens** → **Generate new token**
3. Scope: **Plugin upload** (the only one needed for `publishPlugin`)
4. Copy the token immediately — it is shown **once**

This becomes `PUBLISH_TOKEN`.

---

## 3. Configure GitHub Actions (CI release path)

In the repo: **Settings → Secrets and variables → Actions → New repository secret**.

Add four secrets, paste the file/string contents verbatim (multi-line PEMs are fine — GitHub preserves newlines):

| Secret                 | Value                                                       |
|------------------------|-------------------------------------------------------------|
| `CERTIFICATE_CHAIN`    | full contents of `chain.crt` (`-----BEGIN CERTIFICATE-----` … `-----END CERTIFICATE-----`) |
| `PRIVATE_KEY`          | full contents of `private_encrypted.pem` (`-----BEGIN ENCRYPTED PRIVATE KEY-----` … `-----END ENCRYPTED PRIVATE KEY-----`) |
| `PRIVATE_KEY_PASSWORD` | the password from step 1                                    |
| `PUBLISH_TOKEN`        | the token from step 2                                       |

The release workflow (`.github/workflows/release.yml`, added in Phase 12) maps these into the env at job start:

```yaml
env:
  CERTIFICATE_CHAIN: ${{ secrets.CERTIFICATE_CHAIN }}
  PRIVATE_KEY: ${{ secrets.PRIVATE_KEY }}
  PRIVATE_KEY_PASSWORD: ${{ secrets.PRIVATE_KEY_PASSWORD }}
  PUBLISH_TOKEN: ${{ secrets.PUBLISH_TOKEN }}
```

`providers.environmentVariable("…")` in `build.gradle.kts` reads them from there.

---

## 4. Local signing (optional — only if you need to sign without CI)

Most of the time you don't sign locally. If you do (e.g. testing the release pipeline before pushing a tag), point your shell at the same files:

```bash
# In a shell, NOT committed anywhere
export CERTIFICATE_CHAIN="$(cat ~/.secrets/doppler-intellij/chain.crt)"
export PRIVATE_KEY="$(cat ~/.secrets/doppler-intellij/private_encrypted.pem)"
export PRIVATE_KEY_PASSWORD='change-me-now'
export PUBLISH_TOKEN='perm-…'   # only needed for publishPlugin

./gradlew signPlugin
# ./gradlew publishPlugin   # only when you actually want to push to Marketplace
```

For convenience, keep these in a **gitignored** dotfile and `source` it on demand:

```bash
# ~/.secrets/doppler-intellij.env  — chmod 600, never under the repo
export CERTIFICATE_CHAIN="$(cat ~/.secrets/doppler-intellij/chain.crt)"
export PRIVATE_KEY="$(cat ~/.secrets/doppler-intellij/private_encrypted.pem)"
export PRIVATE_KEY_PASSWORD='…'
export PUBLISH_TOKEN='…'
```

```bash
source ~/.secrets/doppler-intellij.env && ./gradlew signPlugin
```

---

## 5. Verify a signed build

```bash
./gradlew signPlugin
ls build/distributions/
# expect: doppler-0.1.0.zip  doppler-0.1.0-signed.zip
```

The Plugin Verifier and Marketplace upload path use the `-signed.zip` artifact.

---

## Security checklist

- [ ] `chain.crt`, `private_encrypted.pem`, the password, and the publish token live **outside the repo**
- [ ] `.gitignore` already excludes `.gradle/`, `build/`, `.intellijPlatform/` — no signing material lands there
- [ ] No `echo $PRIVATE_KEY` / `printenv` lines committed anywhere (including transient debug code)
- [ ] CI logs do not echo secret env vars (GitHub Actions auto-masks values registered as secrets — do not bypass with `set -x`)
- [ ] If a key or token leaks: revoke the Marketplace token immediately, regenerate the keypair, update the four GH secrets, retag

---

## Rotation

Marketplace tokens have no expiry by default but can be revoked. Cert/key have the validity you set in step 1 (`-days 3650` ⇒ ~10 years). When you rotate:

1. Generate a new keypair (step 1) **with a new subject CN if desired**
2. Update the two GH secrets (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`)
3. The next release will be signed by the new key — Marketplace accepts new signatures from the same plugin owner without ceremony
