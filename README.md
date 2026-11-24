This is a Kotlin Multiplatform project targeting Desktop.

* `/composeApp` is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - `commonMain` is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    `iosMain` would be the right folder for such calls.

## Local Packaging with Gradle

All platform packages can be built locally on their respective host OS with a single Gradle command:

* **Linux (`.flatpak`)**:
  ```bash
  ./gradlew :composeApp:packageFlatpak
  # Output: composeApp/build/compose/binaries/main/flatpak/org.bible.reading-1.0.0.flatpak
  ```
  *(Alias `./gradlew :composeApp:buildFlatpak` is also available.)*

* **Windows (`.exe`)**:
  ```bash
  ./gradlew :composeApp:packageExe
  # Output: composeApp/build/compose/binaries/main/exe/
  ```

* **macOS Apple Silicon `arm64` (`.dmg`)**:
  ```bash
  ./gradlew :composeApp:packageDmg
  # Output: composeApp/build/compose/binaries/main/dmg/
  ```

## CI/CD (GitHub Actions)

Cross-platform builds for Windows (`.exe`), macOS `arm64` (`.dmg`), and Linux (`.flatpak`) are automated via [`.github/workflows/package.yml`](.github/workflows/package.yml) on push, pull request, manual dispatch, and `v*` release tags.

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
