plugins { id("com.android.application") }

android {
    namespace = "com.example.compressor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.compressor"
        minSdk = 26
        // targetSdk 28: نوشتن مستقیم در DCIM بدون MediaStore
        targetSdk = 28
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KS_FILE") ?: "../keystore/release.jks")
            storePassword = System.getenv("KS_PASS")
            keyAlias = "release"
            keyPassword = System.getenv("KS_PASS")
        }
    }

    lint {
        // targetSdk 28 عمداً نگه داشته شده تا نوشتن مستقیم در DCIM کار کند (scoped storage ندارد)
        // و این فقط برای انتشار در Google Play مشکل است؛ APK مستقیم نصب می‌شود
        disable.add("ExpiredTargetSdkVersion")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        // ffmpeg و ffprobe به‌صورت lib*.so اجرایی هستند و باید استخراج شوند
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
