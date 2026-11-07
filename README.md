# Melaan

Melaan is a native Android app for reading and organizing a local comic library.

## Download

The current debug build is [`artifacts/Melaan-debug.apk`](artifacts/Melaan-debug.apk) (version 1.0.12, Android 12 / API 31 or newer).

## Build

Install Android Studio or the Android SDK and a compatible JDK, then run from the project root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## License

This project is not licensed for commercial use. No general open-source license is granted for the project. Third-party components retain their own licenses; see `Prismal/LICENSE` and `app/src/main/res/raw/junrar_license.txt`.
