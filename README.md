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
gradle assembleDebug
```

## Use

1. Install the debug APK on your phone.
2. Share an image from any app.
3. Choose **Share to Lens**.
4. The app opens the Google Lens results in its WebView.

You can also open **Share to Lens** directly, then pick an image or take a
photo with Android's native UI. Camera captures use the in-memory camera
preview returned by Android instead of writing a photo file for the app.

The native app screens follow the system light/dark setting. On Android 12 and
newer, they also use the system dynamic color palette.

## Notes

- This uses Google's public Lens web upload flow, not an official Google Lens
  API.
- The shared image is uploaded to Google so Lens can process it.
- If Google changes the web upload endpoint, the app may need a small update.
