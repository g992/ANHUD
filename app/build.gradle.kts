import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun buildConfigString(value: String?): String {
    val escaped = value.orEmpty()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val versionCodeProp = (project.findProperty("VERSION_CODE") as String?)
    ?.toIntOrNull()
    ?: System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: 1
val versionNameProp = (project.findProperty("VERSION_NAME") as String?)
    ?: System.getenv("VERSION_NAME")
    ?: "0.1.0"

val signingStoreFilePath = System.getenv("SIGNING_STORE_FILE")
    ?: localProperties.getProperty("SIGNING_STORE_FILE")
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    ?: localProperties.getProperty("SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    ?: localProperties.getProperty("SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    ?: localProperties.getProperty("SIGNING_KEY_PASSWORD")
val starlineMapStyleId = System.getenv("STARLINE_MAP_STYLE_ID")
    ?: localProperties.getProperty("STARLINE_MAP_STYLE_ID")
val starlineMapsAccessToken = System.getenv("STARLINE_MAPS_ACCESS_TOKEN")
    ?: System.getenv("STARLINE_MAPS_API_KEY")
    ?: localProperties.getProperty("STARLINE_MAPS_ACCESS_TOKEN")
    ?: localProperties.getProperty("STARLINE_MAPS_API_KEY")
val qnxRootPassword = System.getenv("QNX_ROOT_PASSWORD")
    ?: localProperties.getProperty("qnxRootPassword")
val qnxHost = System.getenv("QNX_HOST")
    ?: localProperties.getProperty("qnxHost")
    ?: "192.168.118.2"
// Prebuilt QNX daemon lives in the repo; GHUDBRIDGELITED_PATH / ghudbridgelitedPath override it for local testing.
val hudBridgeDaemonPath = System.getenv("GHUDBRIDGELITED_PATH")
    ?: localProperties.getProperty("ghudbridgelitedPath")
val hudBridgeDaemonFile = hudBridgeDaemonPath?.takeIf { it.isNotBlank() }?.let { file(it) }
    ?: rootProject.file("third_party/ghudbridgelited/ghudbridgelited")
val hudBridgeDaemonPresent = hudBridgeDaemonFile.isFile
val hudBridgeBuildId = if (hudBridgeDaemonPresent) {
    Regex("ghbl-[A-Za-z0-9-]+")
        .find(String(hudBridgeDaemonFile.readBytes(), Charsets.ISO_8859_1))
        ?.value
        .orEmpty()
} else {
    ""
}
val hudBridgeBundled = hudBridgeDaemonPresent && !qnxRootPassword.isNullOrBlank()
if (!hudBridgeBundled) {
    val reason = if (!hudBridgeDaemonPresent) {
        "ghudbridgelited not found: ${hudBridgeDaemonFile.path}"
    } else {
        "QNX root password is empty (QNX_ROOT_PASSWORD / qnxRootPassword in local.properties)"
    }
    if (System.getenv("CI") == "true") {
        throw GradleException("HUD bridge: $reason")
    }
    logger.warn("HUD bridge disabled in this build: $reason")
}
val hudBridgeAssetsDir = layout.buildDirectory.dir("generated/hudBridgeAssets")

val hasSigning = !signingStoreFilePath.isNullOrBlank() &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank()

android {
    namespace = "com.g992.anhud"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.g992.anhud"
        minSdk = 28
        targetSdk = 36
        versionCode = versionCodeProp
        versionName = versionNameProp

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "STARLINE_MAP_STYLE_ID", buildConfigString(starlineMapStyleId))
        buildConfigField("String", "STARLINE_MAPS_ACCESS_TOKEN", buildConfigString(starlineMapsAccessToken))
        buildConfigField("String", "QNX_ROOT_PASSWORD", buildConfigString(qnxRootPassword))
        buildConfigField("String", "QNX_HOST", buildConfigString(qnxHost))
        buildConfigField("String", "HUD_BRIDGE_BUILD_ID", buildConfigString(hudBridgeBuildId))
        buildConfigField("boolean", "HUD_BRIDGE_BUNDLED", hudBridgeBundled.toString())
    }
    sourceSets {
        getByName("main") {
            assets.srcDir(hudBridgeAssetsDir)
        }
    }

    val releaseSigning = if (hasSigning) {
        signingConfigs.create("release") {
            storeFile = file(signingStoreFilePath!!)
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

val copyHudBridgeDaemon by tasks.registering(Sync::class) {
    into(hudBridgeAssetsDir)
    if (hudBridgeDaemonPresent) {
        from(hudBridgeDaemonFile) {
            rename { "ghudbridgelited" }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(copyHudBridgeDaemon)
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.tananaev.adblib)
    implementation(libs.androidsvg)
    implementation(libs.maplibre.android.sdk)
    implementation(libs.quickjs.wrapper.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.rhino)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
