plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("app.cash.paparazzi") version "1.3.4"
}

// Paparazzi renders real Compose screens to PNG on the JVM -- no emulator,
// no device -- which is how this module gets actual screenshots of the app
// for the README despite this environment having neither.
android {
    namespace = "com.ordertracking.screenshots"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    testImplementation(project(":core:model"))
    testImplementation(project(":core:designsystem"))
    testImplementation(project(":feature:feed"))
    testImplementation(project(":feature:orders"))
    testImplementation(project(":feature:menu"))

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.compose.material3)
    testImplementation(libs.paging.runtime)
    testImplementation(libs.paging.compose)
    testImplementation(libs.junit)
}
