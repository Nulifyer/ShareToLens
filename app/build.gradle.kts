plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.nulifyer.sharetolens"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    val releaseKeystorePath = providers.environmentVariable("SIGNING_KEYSTORE_PATH")
    val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
    val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD")
    val releaseStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD")
    val hasReleaseSigning = releaseKeystorePath.isPresent
            && releaseKeyAlias.isPresent
            && releaseKeyPassword.isPresent
            && releaseStorePassword.isPresent

    defaultConfig {
        applicationId = "dev.nulifyer.sharetolens"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).orElse(1).get()
        versionName = providers.gradleProperty("versionName").orElse("1.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath.get())
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
                storePassword = releaseStorePassword.get()
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
