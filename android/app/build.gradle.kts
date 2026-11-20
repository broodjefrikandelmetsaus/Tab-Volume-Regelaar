import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

extensions.configure<ApplicationExtension> {
    namespace = "nl.jake.volume_remote"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "nl.jake.volume_remote"
        minSdk = 24
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    flavorDimensions += "appRole"
    productFlavors {
        create("receiver") {
            dimension = "appRole"
            applicationIdSuffix = ".receiver"
        }
        create("controller") {
            dimension = "appRole"
            applicationIdSuffix = ".controller"
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter { source = "../.." }
