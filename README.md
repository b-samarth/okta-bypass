# Accessibility Diagnostics

A minimal Kotlin Android app that locally displays accessibility events, exposed node trees, and interactive windows. It uses no network permission and does not capture screenshots, perform gestures/actions, or unlock devices. Diagnostic entries stay in memory until cleared or the process exits; export writes only after the user chooses a destination through Android's document picker.

## Build

Open this directory in Android Studio, install Android SDK Platform 36 if prompted, and let Gradle sync. Use JDK 17 or newer. The project includes a Gradle 8.13 wrapper and uses Android Gradle Plugin 8.13.2. Build from a terminal with `./gradlew assembleDebug`; install to a connected device with `./gradlew installDebug`.

The Kotlin and Java bytecode targets are both Java 17.

## Enable the service

1. Install and open **Accessibility Diagnostics**.
2. Tap **Open Accessibility Settings** (or open **Settings → Accessibility → Installed apps/Downloaded apps**; labels vary by device).
3. Select **Accessibility Diagnostics**, turn on **Use Accessibility Diagnostics**, and confirm Android's disclosure.
4. Return to the app. The live log refreshes automatically. Try another app to inspect its exposed events and accessibility nodes.
5. Use the filter to narrow entries. **Clear** removes the in-memory log. **Export** opens Android's document picker so you choose where to save the text file.

Disable the service from the same Android Accessibility Settings screen when finished. Android may withhold protected, secure, or otherwise unavailable content. This service does not attempt to bypass those restrictions.

## What the log means

```text
EVENT
 ├── event.getText()
 ├── event.contentDescription
 └── source (event's source node, when provided)

NODE
 ├── uniqueId (when the Android version supplies one; not a guaranteed internal node ID)
 ├── class, text, description, hint, view ID, bounds, state, actions
 └── children (recursively exposed through AccessibilityNodeInfo)

WINDOW
 └── available interactive accessibility windows and their root nodes

VIRTUAL NODE
 └── provider-exposed descendants appear in the same node tree. Android does not expose a
     public, universally reliable `isVirtual` flag. `uniqueId` and missing `viewId` are only
     clues; `providerClue` is intentionally labeled as an inference.
```

Accessibility nodes are not a pixel dump or a complete view hierarchy. Apps choose what to expose, and some content may be omitted. This makes the log useful for comparing accessibility output with `uiautomator dump`, whose structure and access constraints differ by Android version and app.
