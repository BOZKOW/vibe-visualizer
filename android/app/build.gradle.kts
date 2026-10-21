// Vibe Visualizer for Android. Built by .github/workflows/android.yml; signed with the release key when it's given
// (the same key as the old app, so this installs over it), otherwise with a debug key.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ks: String? = System.getenv("VV_KEYSTORE")

android {
    namespace = "io.github.bozkow.vibevisualizer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.bozkow.vibevisualizer"
        minSdk = 31
        targetSdk = 36
        // the old WebView app used small build numbers: this one always counts above them
        versionCode = 10000 + (System.getenv("VV_VERSION_CODE") ?: "0").toInt()
        versionName = System.getenv("VV_VERSION_NAME") ?: "1.0.0-dev"
    }

    signingConfigs {
        create("release") {
            if (ks != null) {
                storeFile = file(ks)
                storeType = "pkcs12"
                storePassword = System.getenv("VV_KS_PASS")
                keyAlias = "vv"
                keyPassword = System.getenv("VV_KS_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ks != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
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
        resources {
            excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module", "META-INF/*.md", "META-INF/*.txt", "DebugProbesKt.bin")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")   // Material 3 Expressive: button groups, shape morphing, loading indicator
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.browser:browser:1.10.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Japanese romanization: readings of kanji (the web app uses kuromoji too, through kuroshiro)
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}
