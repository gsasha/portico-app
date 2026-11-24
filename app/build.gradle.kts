import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- PR-G: version scheme ---------------------------------------------------------------------
// versionName is the only input; versionCode is derived from it, so the two can never disagree and
// a build is reproducible (no git count, no clock). versionName = MAJOR.MINOR.PATCH[-STAGE[.N]]
// with STAGE alpha|beta|rc and N 0..9:
//   versionCode = MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + STAGE_RANK + N
//   STAGE_RANK: alpha 10, beta 30, rc 50, a final release 90.
// So 0.1.0-alpha -> 10010, 0.1.0-alpha.2 -> 10012, 0.1.0-rc -> 10050, 0.1.0 -> 10090 and
// 0.1.1-alpha -> 10110: every later version gets a larger code (Android refuses a lower one over
// an installed APK). MINOR and PATCH stay below 100.
val porticoVersionName = "0.7.3-alpha"

fun versionCodeOf(name: String): Int {
    val m = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})(?:-(alpha|beta|rc)(?:\.(\d))?)?""").matchEntire(name)
        ?: throw GradleException("versionName '$name' is not MAJOR.MINOR.PATCH[-alpha|beta|rc[.N]] (see app/build.gradle.kts)")
    val (major, minor, patch, stage, n) = m.destructured
    val rank = when (stage) {
        "alpha" -> 10
        "beta" -> 30
        "rc" -> 50
        else -> 90
    }
    if (stage.isEmpty() && n.isNotEmpty()) throw GradleException("versionName '$name': .N needs a stage")
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + rank + (n.toIntOrNull() ?: 0)
}
// --- end PR-G version scheme ---

// --- PR-G: release signing, secrets outside the repo (P2 plan §7 Q1) ----------------------------
// PORTICO_SIGNING_PROPS names a Java properties file OUTSIDE the repo with storeFile (PKCS12;
// relative to the properties file), storePassword, keyAlias, keyPassword. Without it, debug
// builds and tests work as before and any release packaging task fails with [signingProblem].
// Nothing here prints, logs or exports a password, and nothing goes into BuildConfig.
val signingPropsEnv = "PORTICO_SIGNING_PROPS"
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

var signingProblem: String? = null
val releaseSigning: ReleaseSigning? = run {
    val hint = "A release build is signed with Portico's release key only: set $signingPropsEnv to a " +
        "properties file outside the repo with ${signingKeys.joinToString()} (see docs/development/release-build.md)."
    val path = System.getenv(signingPropsEnv)?.trim().orEmpty()
    if (path.isEmpty()) {
        signingProblem = "$signingPropsEnv is not set. $hint"
        return@run null
    }
    val propsFile = File(path)
    if (!propsFile.isFile) {
        signingProblem = "$signingPropsEnv points at '$path', which is not a readable file. $hint"
        return@run null
    }
    val p = Properties().apply { propsFile.inputStream().use { load(it) } }
    val missing = signingKeys.filter { p.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        // Key NAMES only, never a value.
        signingProblem = "$signingPropsEnv file '$path' lacks ${missing.joinToString()}. $hint"
        return@run null
    }
    val store = File(p.getProperty("storeFile").trim()).let { if (it.isAbsolute) it else File(propsFile.absoluteFile.parentFile, it.path) }
    if (!store.isFile) {
        signingProblem = "the storeFile named in $signingPropsEnv's file ('$store') is not a readable file. $hint"
        return@run null
    }
    ReleaseSigning(store, p.getProperty("storePassword"), p.getProperty("keyAlias").trim(), p.getProperty("keyPassword"))
}
// --- end PR-G release signing ---

// --- DEV-557: the tailnet client is OPTIONAL at build time -------------------------------------
// app/libs/gocore.aar is built by gocore/bind.sh (Go + the NDK) and is git-ignored. When present,
// it is a dependency and src/gomobile/ (GoTailnetEngine) is compiled; when absent, src/nogomobile/
// is compiled instead, the APK has no tailnet client, and Setup says "not built into this APK".
// Every unit test runs either way. The signed release build runs gocore/bind.sh first.
val goCoreAar: File = file("libs/gocore.aar")
val hasGoCore: Boolean = goCoreAar.isFile
logger.lifecycle("Portico tailnet client: " + if (hasGoCore) "built in (app/libs/gocore.aar)" else "NOT built in (no app/libs/gocore.aar; run gocore/bind.sh)")
// --- end DEV-557 ---

android {
    namespace = "com.meda.portico"
    compileSdk = 34

    defaultConfig {
        // The installed app id and Firebase package name; independent of `namespace` (the code package).
        applicationId = "com.meda.portico"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeOf(porticoVersionName) // PR-G: derived, see the scheme above
        versionName = porticoVersionName

        // Native code is packaged for arm64-v8a ONLY. The WebRTC library ships four ABIs and
        // gocore.aar (gocore/bind.sh) is arm64-only by design; without this filter the APK
        // carries ~50 MB of x86/x86_64/armeabi-v7a WebRTC that no target phone loads. A pure
        // 32-bit ARM or x86 device is refused at install (INSTALL_FAILED_NO_MATCHING_ABIS, from
        // general Android behaviour, not re-checked on a device); before this filter it installed
        // but had no tailnet library. NativeAbiTest pins this set. See docs/development/gocore-and-apk.md ("APK size and native ABIs").
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // --- PR-G: release build ---
    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
                storeType = "pkcs12"
                enableV1Signing = false // minSdk 26: v2+ is what every supported phone checks
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Never the debug key and never "unsigned": with no release key the packaging tasks
            // fail (checkReleaseSigning below) instead of producing a release that is not one.
            signingConfig = signingConfigs.findByName("release")
            isDebuggable = false
            isJniDebuggable = false
            // No R8 for the first release: every unit test runs unminified, nothing on this host
            // can exercise a minified APK, and runtime-configured Firebase, OkHttp and
            // kotlinx-serialization would each need keep rules proved on a phone. See docs/development/release-build.md.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    // --- end PR-G release build ---

    // --- PR-D: JVM unit tests (MockWebServer) on JUnit 5 ---
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
    // --- end PR-D ---

    // DEV-557: exactly one TailnetEngines implementation is compiled (see hasGoCore above).
    sourceSets {
        getByName("main") {
            java.srcDir(if (hasGoCore) "src/gomobile/java" else "src/nogomobile/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// --- PR-E: MergedManifestTest reads the merged debug manifest (what the APK really declares) ---
val mergedDebugManifest = layout.buildDirectory.file(
    "intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
)
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn("processDebugManifest")
        inputs.file(mergedDebugManifest)
        systemProperty("portico.mergedManifest", mergedDebugManifest.get().asFile.absolutePath)
        // PR-J: MergedManifestTest also reads the network security config the manifest names.
        val nsc = layout.projectDirectory.file("src/main/res/xml/network_security_config.xml")
        inputs.file(nsc)
        systemProperty("portico.networkSecurityConfig", nsc.asFile.absolutePath)
    }
}
// --- end PR-E ---

// --- ABI filter: NativeAbiTest lists lib/<abi>/ in the debug APK itself (the filter applies at packaging) ---
val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn("packageDebug")
        inputs.file(debugApk)
        systemProperty("portico.debugApk", debugApk.get().asFile.absolutePath)
    }
}
// --- end ABI filter ---

// --- PR-G: no release APK or bundle without the release key -------------------------------------
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Fails unless $signingPropsEnv names a usable release-signing properties file."
    val problem = signingProblem
    doLast {
        if (problem != null) throw GradleException("Portico release signing: $problem")
    }
}
// Hooked on packaging only, so `test`, `lint` and every debug task still run without the key.
// mustRunAfter makes the check run before any release compilation when it is in the graph, so a
// missing key fails in seconds, not after the compile.
tasks.configureEach {
    when (name) {
        "packageRelease", "packageReleaseBundle", "assembleRelease", "bundleRelease" -> dependsOn(checkReleaseSigning)
        "preReleaseBuild" -> mustRunAfter(checkReleaseSigning)
    }
}
// --- end PR-G ---

// --- PR-H: SignalingClientTest replays core's vendored ws_v3.json; declare it as an input ---
val wsContract = rootProject.file("core/src/test/resources/ws_v3.json")
tasks.withType<Test>().configureEach {
    inputs.file(wsContract)
    systemProperty("portico.wsContract", wsContract.absolutePath)
}
// --- end PR-H ---

// --- DEV-544: the enrolment contract fixture (core's vendored enroll_v1.json) is the replay's input ---
val enrollContract = rootProject.file("core/src/test/resources/enroll_v1.json")
tasks.withType<Test>().configureEach {
    inputs.file(enrollContract)
    systemProperty("portico.enrollContract", enrollContract.absolutePath)
}
// --- end DEV-544 ---

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0") // 0.6.96: the in-call screen's door buttons confirm with an app-level prompt while the phone is locked (no unlock)
    implementation("androidx.security:security-crypto:1.1.0") // PR-C: EncryptedSharedPreferences (§4.4, §4.7); 1.1.0 is the current stable

    // --- PR-D (HA WebSocket core + registration) ---
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // --- end PR-D ---

    // --- PR-E (Firebase messaging; runtime FirebaseOptions, no google-services plugin/json) ---
    // BoM 34.19.0 is the first BoM that pins firebase-messaging 25.1.3 (34.18.0 -> 25.1.2).
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
    // --- end PR-E ---

    // --- Answering a door call: WebRTC. The card's exchange (non-trickle offer, SFU answer) needs a
    // real WebRTC stack on the phone; none was in the app. io.github.webrtc-sdk:android is the
    // maintained build of Google's libwebrtc (Google's own org.webrtc:google-webrtc stopped at
    // 1.0.32006 in 2023). 137.7151.05 is the newest 137 build; the app does not run it anywhere
    // until a user presses Answer. Ships native libraries for four ABIs; defaultConfig's ndk.abiFilters keeps arm64-v8a only.
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    // --- end answering ---

    // --- DEV-557: gocore/ (a separate Go module) gomobile-compiled by gocore/bind.sh. Optional. ---
    if (hasGoCore) implementation(files(goCoreAar))
}
