// Root build file. The app/ + gocore/ layout follows design doc §9.8 (unpublished; see
// docs/architecture/design-sources.md and docs/development/repository-map.md).
//
// Versions are pinned to what builds on the host toolchain (Gradle 8.9, JDK 17) and
// resolves firebase-messaging 25.1.3 (which pulls kotlin-stdlib 2.0.21): see
// docs/development/build-and-test.md and docs/reference/versions.md. AGP 8.7.x is the newest line that runs on Gradle 8.9.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false
}
