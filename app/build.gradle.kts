import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * バージョンはリリースワークフロー（Git タグ）から差し込む。
 * 指定が無ければ手元ビルド用の既定値を使う。
 */
val appVersionName = (project.findProperty("appVersionName") as String?)
    ?: System.getenv("APP_VERSION_NAME")
    ?: "1.0.0"
val appVersionCode = ((project.findProperty("appVersionCode") as String?)
    ?: System.getenv("APP_VERSION_CODE"))
    ?.toIntOrNull()
    ?: 1

/** アプリ内アップデートが更新を探しに行く GitHub リポジトリ。 */
val githubRepository = (project.findProperty("githubRepository") as String?)
    ?: "TaichiOikawa/MirroringCamera-Android"

/**
 * リリース署名は `keystore.properties`（コミットしない）か環境変数から読む。
 * どちらも無い場合は debug 鍵にフォールバックし、鍵を持たない環境でも
 * `assembleRelease` が通るようにする。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey)

val releaseStoreFilePath = signingValue("storeFile", "RELEASE_STORE_FILE")
val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
    rootProject.file(releaseStoreFilePath).exists()

android {
    namespace = "com.zundataichi.mirroringcamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zundataichi.mirroringcamera"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUILD_TIME", "\"${SimpleDateFormat("yyyy.MM.dd-HH.mm.ss").format(Date())}\"")
        // アプリ内アップデートが参照する GitHub リポジトリ
        buildConfigField("String", "GITHUB_REPOSITORY", "\"$githubRepository\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 署名鍵が無い環境では debug 鍵で署名する。
            // 未署名 APK は端末にインストールできず、CI の疎通確認にもならないため。
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // Network
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // WebRTC (real-time preview)
    implementation(libs.stream.webrtc.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}