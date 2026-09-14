import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun signingValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }

// Optional local/CI signing. If present, release variants are signed automatically.
val bbSigningStoreFile = signingValue("BB_SIGNING_STORE_FILE")
val bbSigningStorePassword = signingValue("BB_SIGNING_STORE_PASSWORD")
val bbSigningKeyAlias = signingValue("BB_SIGNING_KEY_ALIAS")
val bbSigningKeyPassword = signingValue("BB_SIGNING_KEY_PASSWORD")
val bbSigningStoreType = signingValue("BB_SIGNING_STORE_TYPE")
val bbHasSigning = !bbSigningStoreFile.isNullOrBlank() &&
    !bbSigningStorePassword.isNullOrBlank() &&
    !bbSigningKeyAlias.isNullOrBlank() &&
    !bbSigningKeyPassword.isNullOrBlank()

android {
    namespace = "com.bobbot"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bobbot"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "1.3.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_OWNER", "\"Robertg761\"")
        buildConfigField("String", "GITHUB_REPO", "\"BobBot\"")
    }

    if (bbHasSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(bbSigningStoreFile!!)
                if (!bbSigningStoreType.isNullOrBlank()) {
                    storeType = bbSigningStoreType
                }
                storePassword = bbSigningStorePassword
                keyAlias = bbSigningKeyAlias
                keyPassword = bbSigningKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Kept unminified, like the other sideloaded apps, so update installs are predictable.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (bbHasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
