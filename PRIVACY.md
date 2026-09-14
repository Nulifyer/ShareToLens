# Privacy

Share to Lens is designed to handle one image at a time without asking for
access to your photo library, camera, or location.

## What happens on your device

When you share or choose an image, Android grants the app temporary access to
that image. When you take a photo, the camera writes to a private temporary
file owned by Share to Lens. The app never adds that photo to the system photo
library.

Before upload, Share to Lens:

- Rejects source files larger than 25 MB.
- Reads the EXIF orientation so the pixels remain upright.
- Scales the image to at most 2048 pixels on its longest edge.
- Draws the pixels onto a fresh opaque bitmap.
- Writes a new JPEG without copying EXIF, GPS, timestamp, camera, or other
  source-file metadata.

This process removes file metadata. It cannot remove information visible in
the picture, such as faces, text, addresses, landmarks, reflections, or other
location clues.

## What leaves your device

Share to Lens sends the processed JPEG and its new pixel dimensions to
Google's Lens web upload endpoint. Google also receives information normally
included in a web request, such as your IP address and browser user agent.
Google's terms and privacy policy govern its processing and retention.

The app does not send the original file or its original EXIF metadata. It has
no analytics, advertising, crash-reporting, account, or telemetry SDK.

## Local retention

The app keeps temporary source and processed files only while preparing,
uploading, or offering a retry. It deletes them after success, cancellation,
or a normal app exit. On startup, it also removes temporary files left for
more than six hours by an interrupted or killed process.

Share to Lens does not keep a search-history database. Android backup and
device transfer are disabled for all app data.

The in-app browser needs JavaScript, first-party cookies, and DOM storage for
Google Lens. It blocks cleartext traffic, file and content access, geolocation,
mixed content, pop-up windows, and third-party cookies. Top-level navigation
inside the app is limited to Lens and Google account pages. Other links open
in your regular browser.

The app clears cookies and WebView storage before a new process starts a
session and after a normal session exit. Android may kill a process without
calling cleanup code, so end-of-session deletion cannot be guaranteed. The
next normal launch clears browser storage before processing another image.

## Permissions

The app requests one Android platform permission:

```text
android.permission.INTERNET
```

AndroidX also generates a signature-protected permission used only to prevent
other apps from sending unsafe broadcasts to Share to Lens. It grants no
device or user-data access. The app does not request camera, location,
contacts, storage, or photo-library permissions.

## Scope

This document describes Share to Lens itself. The project is independent from
Google, and Google Lens is a separate service with separate policies.
