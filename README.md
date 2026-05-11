# Share to Lens

A tiny Android share target for sending an image to Google Lens in the browser.

The app does not depend on the Google app or the Google Lens app. When you share
an image to **Share to Lens**, it uploads that image to the Google Lens web
endpoint and opens the returned result page in your default browser.

## Build

Open this folder in Android Studio, let it sync Gradle, then build or run the
`app` configuration.

From a terminal with Gradle and the Android SDK configured:

```powershell
gradle assembleDebug
```

## Use

1. Install the debug APK on your phone.
2. Share an image from any app.
3. Choose **Share to Lens**.
4. Your browser opens with the Google Lens results.

## Notes

- This uses Google's public Lens web upload flow, not an official Google Lens
  API.
- The shared image is uploaded to Google so Lens can process it.
- If Google changes the web upload endpoint, the app may need a small update.
