# Share to Lens

Share images to Google Lens without installing the Google app.

**Share to Lens** adds a simple Android share target. Share an image from any
app, choose **Share to Lens**, and the app uploads it to the Google Lens web
upload flow, then opens the Lens result page in an in-app browser.

## Install

Download the latest APK from the
[GitHub Releases page](https://github.com/Nulifyer/ShareToLens/releases).

Or add it directly to Obtanium:

[![Get it on Obtanium](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png)](https://apps.obtainium.imranr.dev/redirect.html?r=obtainium%3A%2F%2Fadd%2Fhttps%3A%2F%2Fgithub.com%2FNulifyer%2FShareToLens)

You can also track updates with
[Obtanium](https://github.com/ImranR98/Obtainium):

1. Add this repository URL:

   ```text
   https://github.com/Nulifyer/ShareToLens
   ```

2. Use this APK filter:

   ```text
   ShareToLens-.*\.apk$
   ```

3. Leave prereleases disabled unless you want test builds.

## Use

1. Open any app with an image.
2. Tap Android's share button.
3. Choose **Share to Lens**.
4. Wait for the Lens result page to open.

You can also open **Share to Lens** directly, then pick an image or take a
photo.

## Privacy

- Shared images are uploaded to Google so Lens can process them.
- The app does not require the Google app or Google Lens app.
- Browser cookies and WebView storage are cleared when the app closes.

## Notes

- This uses Google's public Lens web upload flow, not an official Google Lens
  API.
- If Google changes that web flow, uploads may stop working until the app is
  updated.
- If you previously installed a debug build, uninstall it before installing a
  GitHub release APK. Android treats debug and release builds as differently
  signed apps.
