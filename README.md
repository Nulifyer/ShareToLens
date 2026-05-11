# Share to Lens

A tiny Android share target for sending an image to Google Lens in an in-app browser.

The app does not depend on the Google app or the Google Lens app. When you share
an image to **Share to Lens**, it uploads that image to the Google Lens web
endpoint and opens the returned result page inside the app.

## Build

Open this folder in Android Studio, let it sync Gradle, then build or run the
`app` configuration.

From a terminal with Gradle and the Android SDK configured:

```powershell
.\gradlew.bat assembleDebug
```

## Use

1. Install the debug APK on your phone.
2. Share an image from any app.
3. Choose **Share to Lens**.
4. The app opens the Google Lens results in its WebView.

You can also open **Share to Lens** directly, then pick an image or take a
photo with Android's native UI.

The native app screens follow the system light/dark setting. On Android 12 and
newer, they also use the system dynamic color palette.

## Release

GitHub Actions builds release APKs from semver tags like `v1.2.3`. Each release
uploads:

- `ShareToLens-v1.2.3.apk`
- `ShareToLens-v1.2.3.apk.sha256`

Create a release signing key once:

```powershell
keytool -genkeypair `
  -v `
  -keystore sharetolens-release.jks `
  -storetype JKS `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000 `
  -alias sharetolens
```

Base64-encode the keystore for GitHub:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("sharetolens-release.jks")) | Set-Content -NoNewline sharetolens-release.jks.base64
```

Add these repository secrets in GitHub:

- `SIGNING_KEYSTORE_BASE64`: contents of `sharetolens-release.jks.base64`
- `SIGNING_KEY_ALIAS`: `sharetolens`
- `SIGNING_KEY_PASSWORD`: key password from `keytool`
- `SIGNING_STORE_PASSWORD`: store password from `keytool`

Publish a release:

```powershell
git tag v1.2.3
git push origin v1.2.3
```

For Obtanium, add this GitHub repository URL as a GitHub source:

```text
https://github.com/Nulifyer/ShareToLens
```

The repository must be public, or Obtanium needs a GitHub personal access token.
Use this APK filter:

```text
ShareToLens-.*\.apk$
```

Leave prereleases disabled unless you intentionally publish prerelease builds.
Keep using the same signing key for all future releases; changing it prevents
Android from installing updates over the existing app.

If you previously installed a debug build from Android Studio or Gradle, uninstall
it before installing the first GitHub release APK. Debug and release APKs use
different signing keys, so Android will not treat the first release APK as an
update to the debug build.

## Notes

- This uses Google's public Lens web upload flow, not an official Google Lens
  API.
- The shared image is uploaded to Google so Lens can process it.
- If Google changes the web upload endpoint, the app may need a small update.
