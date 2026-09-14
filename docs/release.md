# Release signing and verification

Every public build must use the same release key. Android will reject an
update signed with a different key, and losing the key prevents future
versions from updating existing installations.

Keep two encrypted offline backups. GitHub Actions secrets are a deployment
copy, not a recoverable backup.

## Create or reuse the key

On Windows, the repository helper creates the keystore and can upload its
values through GitHub CLI:

```powershell
gh auth login
.\scripts\setup-release-secrets.ps1 -Upload
```

On Linux or macOS, create the same key with the JDK tool:

```bash
mkdir -p .release
keytool -genkeypair \
  -keystore .release/sharetolens-release.jks \
  -alias sharetolens \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

The `.release` directory and keystore extensions are ignored by Git. Never
commit the key or its passwords.

## Configure GitHub Actions

The release workflow reads these repository secrets:

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_STORE_PASSWORD`

The PowerShell helper can set them. On Linux or macOS, use GitHub CLI:

```bash
base64 -w0 .release/sharetolens-release.jks | gh secret set SIGNING_KEYSTORE_BASE64
gh secret set SIGNING_KEY_ALIAS
gh secret set SIGNING_KEY_PASSWORD
gh secret set SIGNING_STORE_PASSWORD
```

On macOS, replace `base64 -w0` with `base64 | tr -d '\n'`.

## Publish

Create a semantic version tag and push it:

```bash
git tag -s v1.0.0 -m "Share to Lens 1.0.0"
git push origin v1.0.0
```

The workflow runs unit tests and lint, builds an R8-shrunk signed APK, verifies
its signature, and publishes:

- `ShareToLens-vX.Y.Z.apk`
- `ShareToLens-vX.Y.Z.apk.sha256`
- `ShareToLens-vX.Y.Z.apk.signature.txt`
- A GitHub build provenance attestation.

Confirm the release is immutable in the repository settings before treating
the assets as final.

## Verify a downloaded release

Check the checksum from the release directory:

```bash
sha256sum --check ShareToLens-v1.0.0.apk.sha256
```

Inspect the signer with Android SDK Build Tools:

```bash
apksigner verify --verbose --print-certs ShareToLens-v1.0.0.apk
```

Compare the certificate SHA-256 digest with the `.signature.txt` file from a
trusted release. The digest must remain the same across updates.

Verify the GitHub attestation with GitHub CLI:

```bash
gh attestation verify ShareToLens-v1.0.0.apk --repo Nulifyer/ShareToLens
```
