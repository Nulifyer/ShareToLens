# Release Setup

Use the helper script to create the release signing keystore and configure
GitHub Actions secrets.

## One-Time Signing Setup

Generate a new keystore and print manual secret instructions:

```powershell
.\scripts\setup-release-secrets.ps1
```

Generate a new keystore and upload secrets with GitHub CLI:

```powershell
gh auth login
.\scripts\setup-release-secrets.ps1 -Upload
```

Generated files are written under `.release/`, which is ignored by Git.

## Existing Keystore

If `.release/sharetolens-release.jks` already exists, the script reuses it.
With `-Upload`, it prompts for the existing keystore and key passwords, then
sets these repository secrets:

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_STORE_PASSWORD`

## Rotate Only If Necessary

Keep the same release keystore for all future versions. Android will reject app
updates signed with a different key.

To intentionally replace a local test keystore:

```powershell
.\scripts\setup-release-secrets.ps1 -Force
```
