// Pure-Kotlin core: protocol, decision and parsing logic with ZERO android.* imports, so
// it is tested on the JVM with no device or emulator (P2 plan §2.2, §3).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // --- PR-D (HA WebSocket core + registration): JSON tree API only, no compiler plugin ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // --- end PR-D ---
    // --- PR-C2 (scan the enrollment QR): ZXing core, the version the old intercom app pins by
    // hash (build.sh ZXVER). Pure Java, no Android code, so the frame decoder and the QR-size
    // measurement are JVM-tested here. The app gets it through :core.
    implementation("com.google.zxing:core:3.5.3")
    // --- end PR-C2 ---
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
