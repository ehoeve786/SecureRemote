plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.secrm.core.server.mcp"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties"
        )
    }
}
dependencies {
    api(project(":core:domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
}
