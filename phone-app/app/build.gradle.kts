plugins {
    id("com.android.application")
}

val ksFile = findProperty("KS_FILE") as String?

android {
    namespace = "com.qiangge.phone"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.qiangge.phone"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (ksFile != null) {
            create("rel") {
                storeFile = file(ksFile)
                storePassword = findProperty("KS_STORE_PASSWORD") as String?
                keyAlias = findProperty("KS_ALIAS") as String?
                keyPassword = findProperty("KS_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile != null) signingConfig = signingConfigs.getByName("rel")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
