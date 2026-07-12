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
        versionCode = 1
        versionName = "0.1.0"

        // 收敛到目标 ABI,大幅瘦身
        ndk {
            // 无 native code,留个空模板注释
            // abiFilters += listOf("arm64-v8a", "x86_64")
        }
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
            // 没签 releaseKey 的话,LSPosed/手机不会装;本地测试用 debug 别用 release
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

    // CI (GitHub Actions) 上不能因为 lint 警告就 fail,主要怕 hook stub 反射失败那一类
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 仅在编译期需要,运行时由 LSPosed 注入,不能 implementation
    compileOnly(libs.xposed.api)
}
