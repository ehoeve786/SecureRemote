plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.secrm"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.secrm"
        minSdk = 30 // auth-bound Keystore key (setUserAuthenticationParameters)
        targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" } // phones only; no emulator/32-bit builds
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // CI signs with one fixed key (from repo secrets) so builds update in place.
    System.getenv("SIGNING_PASSWORD")?.let { pw ->
        val ci = signingConfigs.create("ci") {
            storeFile = rootProject.file("signing.jks")
            storePassword = pw; keyAlias = "secrm"; keyPassword = pw
        }
        buildTypes.getByName("debug").signingConfig = ci
        buildTypes.getByName("release").signingConfig = ci
    }
    packaging {
        // Compress native libraries in the APK (the Go networking library is large).
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
        resources.excludes += setOf(
            "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
            "META-INF/versions/**/OSGI-INF/",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "META-INF/io.netty.versions.properties",
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    implementation(project(":core:design"))
    implementation(project(":core:security"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:ssh"))
    implementation(project(":core:ssh-tools"))
    implementation(project(":core:terminal"))
    implementation(project(":core:domain"))
    implementation(project(":core:server-mcp"))
    implementation(project(":core:server-http"))
    implementation(project(":core:mcp"))
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
