// AEmulator Manager — the root/Xposed management surface that ships INSIDE the guest.
// Zero dependencies on purpose: plain android.* views, no androidx, no compose — the APK
// is ~40 KB and builds in seconds, so CI never becomes the bottleneck for it.
import java.util.Properties

plugins {
    id("com.android.application")
}

// same signing story as :app: keystore.properties at the repo root (not in git), debug
// builds ride the release key when it exists so an update installs over the last one
val signProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val canSign = signProps.getProperty("storeFile")?.let { rootProject.file(it).isFile } == true

android {
    namespace = "app.aemu.manager"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // 28 on purpose: the manager runs inside guest firmware, and a low target keeps
        // legacy external storage behaviour so /sdcard paths behave like the rest of AEmulator
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 7
        versionName = "0.0.0.7"
    }

    signingConfigs {
        if (canSign) create("release") {
            storeFile = rootProject.file(signProps.getProperty("storeFile"))
            storePassword = signProps.getProperty("storePassword")
            keyAlias = signProps.getProperty("keyAlias")
            keyPassword = signProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

// sh: ./gradlew :manager:copyManagerApk -> release-apks/<version>/AEmulatorManager.apk
tasks.register<Copy>("copyManagerApk") {
    dependsOn("assembleRelease")
    val v = android.defaultConfig.versionName
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "AEmulatorManager.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("release-apks/$v"))
}
