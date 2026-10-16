plugins {
    id("com.android.application")
}

// 和管理器 apksign DSL 用同一套属性名，CI 里复用同一份 Secrets 无需额外映射
val ksFile = findProperty("KEYSTORE_FILE") as String?

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
                storePassword = findProperty("KEYSTORE_PASSWORD") as String?
                keyAlias = findProperty("KEY_ALIAS") as String?
                keyPassword = findProperty("KEY_PASSWORD") as String?
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
