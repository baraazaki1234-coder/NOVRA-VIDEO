# NOVRA Android

Native Android application built with Kotlin and Jetpack Compose. This is a separate Android implementation; the existing browser-based local library on `main` remains untouched.

## Current foundation
- Kotlin + Jetpack Compose navigation shell
- Native Android application module
- Media3 ExoPlayer and MediaSession dependencies
- Room and DataStore dependencies for the local library and preferences
- Android CI workflow producing a debug APK artifact

## Build
Requires JDK 17 and Gradle 8.13:
```bash
gradle assembleDebug
```

The current screens are the initial UI foundation. Device media scanning, persistent library, playback service, permissions flow, and settings are subsequent implementation stages.
