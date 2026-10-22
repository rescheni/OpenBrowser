plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openbrowser.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openbrowser.mobile"
        minSdk = 28
        targetSdk = 34
        versionCode = 11
        versionName = "1.8.0"
        resourceConfigurations += listOf("zh", "en")

        // GeckoView 的引擎库覆盖全部 ABI，只保留手机常用的 arm64，避免 APK 膨胀到数百 MB
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    lint {
        // 依赖版本为配合 compileSdk 34 固定，不跟进每个新版本提示。
        disable += "GradleDependency"
        // GeckoView 内置的 ExoPlayer 会调用通知 API，但应用本身不发送通知：
        // 已在清单声明 POST_NOTIFICATIONS，运行时也不会主动申请，忽略第三方代码提示。
        disable += "NotificationPermission"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    // Firefox 内核（GeckoView），由 Mozilla 预编译发布，本地不需要编译 Chromium/Gecko
    implementation("org.mozilla.geckoview:geckoview:128.0.20240725162350")
}
