plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// -PnoAbiSplits builds one APK with both ABIs (F-Droid needs distinct
// versionCodes per APK, and the splits share one).
val enableAbiSplits = !project.hasProperty("noAbiSplits") &&
    gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }

// The in-app updater's repository slug is deployment configuration, not source
// data: the tracked tree must not embed a personal account name. A published
// build supplies the real slug through OPENTAILCAT_REPO_SLUG or
// -PopentailcatRepoSlug; the default is a neutral placeholder used by tests.
val repoSlug = (System.getenv("OPENTAILCAT_REPO_SLUG")
    ?: project.findProperty("opentailcatRepoSlug") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "opentailcat/OpenTailcat"

android {
    namespace = "com.tailcat.vpn"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.tailcat.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 43
        versionName = "1.5.4"

        buildConfigField("String", "REPO_SLUG", "\"$repoSlug\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (!enableAbiSplits) {
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    val releaseStoreFile = System.getenv("OPENTAILCAT_RELEASE_STORE_FILE")
    if (!releaseStoreFile.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("OPENTAILCAT_RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("OPENTAILCAT_RELEASE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("OPENTAILCAT_RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (!releaseStoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
        create("development") {
            // Downloadable test builds use release optimization without the
            // debug variant's Compose tooling or a production signing key.
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    // "github" ships the in-app updater (GitHub Releases APKs);
    // "fdroid" compiles it out so F-Droid builds never fetch or install APKs.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
    }
    splits {
        abi {
            // ABI splits cannot be combined with bundleRelease (AGP issue 402800800).
            isEnable = enableAbiSplits
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
    sourceSets {
        getByName("test").resources.srcDir(rootProject.file("core-engine/testdata"))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)

    // Checked-in Go Mobile AAR (core-engine/build-aar.sh). Named explicitly so
    // stray local files in libs/ (such as gomobile's sources jar) never ship.
    implementation(files("libs/libtailcat.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
