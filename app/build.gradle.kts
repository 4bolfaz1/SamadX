plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ir.abdev.samadx"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ir.abdev.samadx"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
}
