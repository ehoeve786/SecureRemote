plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.secrm.core.server.http"
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
            "META-INF/io.netty.versions.properties",
            "META-INF/versions/9/module-info.class"
        )
    }
}
dependencies {
    api(project(":core:server-mcp"))
    implementation(project(":core:domain"))
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    implementation("io.ktor:ktor-server-cio:3.5.1")
    implementation("io.ktor:ktor-server-core:3.5.1")
    implementation("io.ktor:ktor-server-status-pages:3.5.1")
}
