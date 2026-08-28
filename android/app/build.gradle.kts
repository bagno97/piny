plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.piny.waga"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.piny.waga"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }

    signingConfigs {
        create("release") {
            val ks = file(providers.gradleProperty("wagaKeystore").getOrElse("../keystore/waga.jks"))
            if (ks.exists()) {
                storeFile = ks
                storePassword = providers.gradleProperty("wagaStorePassword").getOrElse("")
                keyAlias = providers.gradleProperty("wagaKeyAlias").getOrElse("waga")
                keyPassword = providers.gradleProperty("wagaKeyPassword").getOrElse("")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (file("../keystore/waga.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.logging", "stdout") }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
