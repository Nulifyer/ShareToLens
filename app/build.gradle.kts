plugins {
    id("com.android.application")
}

android {
    namespace = "dev.nulifyer.sharetolens"
    compileSdk = 36

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
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).orElse(1).get()
        versionName = providers.gradleProperty("versionName").orElse("1.0").get()
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
