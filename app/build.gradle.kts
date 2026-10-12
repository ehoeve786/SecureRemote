plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.secrm"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.secrm"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties"
        )
    }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(project(":core:design"))
    implementation(project(":core:security"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:ssh"))
    implementation(project(":core:domain"))
    implementation(project(":core:ssh-tools"))
    implementation(project(":core:mcp"))
    implementation(project(":core:server-mcp"))
    implementation(project(":core:server-http"))
}
