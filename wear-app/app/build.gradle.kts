import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// 登録APIの接続先とAPIキーは local.properties（Git管理外）か -P で渡す
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun siteAlertProperty(name: String, default: String): String =
    (findProperty(name) as String?) ?: localProperties.getProperty(name) ?: default

android {
    namespace = "net.howlrs.sitealert"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.howlrs.sitealert"
        minSdk = 33 // Wear OS 4 以降（D-08）
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        buildConfigField("String", "REGISTER_API_KEY", "\"${siteAlertProperty("siteAlert.apiKey", "")}\"")
    }

    buildTypes {
        debug {
            // エミュレーターからホスト（WSL）の 127.0.0.1:8765 へ届く
            buildConfigField(
                "String", "REGISTER_URL",
                "\"${siteAlertProperty("siteAlert.registerUrl.debug", "http://10.0.2.2:8765/api/register")}\"",
            )
        }
        release {
            isMinifyEnabled = false
            buildConfigField(
                "String", "REGISTER_URL",
                "\"${siteAlertProperty("siteAlert.registerUrl.release", "https://example.com/api/register")}\"",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.installations)

    testImplementation(libs.junit)
}
