plugins {
    id("com.android.library")
}
android {
    namespace = "com.secrm.core.server.http"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
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
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-status-pages:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
