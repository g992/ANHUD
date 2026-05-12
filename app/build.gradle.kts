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

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.tananaev.adblib)
    implementation(libs.androidsvg)
    implementation(libs.maplibre.android.sdk)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
