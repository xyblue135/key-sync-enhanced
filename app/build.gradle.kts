import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("kotlinx-serialization")
}

// 签名配置来源：① 根目录 keystore.properties（本地，不入库）② gradle 属性（CI 写入 ~/.gradle/gradle.properties）
// 两者都缺失时（如仅打 debug 包）跳过签名，避免配置阶段直接失败
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val keystoreProp: (String) -> String? = { name ->
    keystoreProperties.getProperty(name) ?: project.findProperty(name)?.toString()
}

android {
    sourceSets["main"].assets.srcDir("$rootDir/data")

    namespace = "com.devoid.keysync"
    compileSdk = 36

    signingConfigs {
        create("release") {
            keystoreProp("storeFile")?.let { path ->
                storeFile = file(path)
                storePassword = keystoreProp("storePassword")
                keyAlias = keystoreProp("keyAlias")
                keyPassword = keystoreProp("keyPassword")
            }
        }
    }


    defaultConfig {
        applicationId = "com.devoid.keysync"
        minSdk = 29
        targetSdk = 36
        versionCode = 19
        versionName = "1.43-android16"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".fpsdebug"
            versionNameSuffix = "-fps-sprintfix1"
        }
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)
    ksp("com.google.dagger:hilt-android-compiler:2.55")
    implementation("com.google.dagger:hilt-android:2.55")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.api)
    implementation(libs.provider)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}