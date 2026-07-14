plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.lsposed.fuckthevideo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.lsposed.fuckthevideo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 11
        versionName = "0.3.2"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 默认 debug 签名,要换正式签名见 README
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = false
        viewBinding = false
    }

    // ---- libxposed 必需 ----
    // 把 META-INF/xposed/* 合并进 APK(framework 通过 java_init.list 读取入口)
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    // CI 上不能因为 lint 警告就 fail
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 运行时由 Vector 框架注入,故 compileOnly。不能 implementation。
    compileOnly(libs.libxposed.api)
}
