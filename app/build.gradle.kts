import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.glebskorobogatov.subscriptiontracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glebskorobogatov.subscriptiontracker"
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1"
    }

    // Optional separate sandbox for F-04 system/device testing; release identity is unchanged.
    buildTypes.getByName("debug") {
        val isolated = providers.gradleProperty("f04DeviceTest").orNull == "true"
        if (isolated) {
            applicationIdSuffix = ".f04test"
            versionNameSuffix = "-f04-test"
        }
        manifestPlaceholders["debugAppLabel"] = if (isolated) "@string/f04_test_app_name" else "@string/app_name"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Read-only preflight uses the unchanged Room snapshots as its historical source of truth.
    sourceSets.getByName("main").assets.srcDir("schemas")
    sourceSets.getByName("androidTest").assets.srcDir("schemas")

    // Give the JVM smoke test the actual manifest and string resources.
    sourceSets.getByName("test").resources.apply {
        srcDir("src/main")
        include("AndroidManifest.xml", "res/values/strings.xml", "res/values/notifications.xml")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.work.runtime)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.uiautomator)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.coroutines.test)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.compose.icons)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.test)
    debugImplementation(libs.androidx.compose.test.manifest)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.tooling.preview)
    debugImplementation(libs.androidx.compose.tooling)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
}
