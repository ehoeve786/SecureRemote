import javax.inject.Inject

plugins {
    id("com.android.library")
}

android {
    namespace = "com.secrm.gonet"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
}

// SecureRemote's Go networking (tsnet Tailscale node and userspace WireGuard), bound to Java with
// gomobile. Copied from PocketPilot's network/gonet. One library, because a process can hold only
// one Go runtime. Building it needs Go, gomobile and the Android NDK, which CI installs.
val goSources = layout.projectDirectory.dir("go")
val gomobileAar = layout.buildDirectory.file("gomobile/srnet.aar")
val gomobileClasses = layout.buildDirectory.file("gomobile/classes/classes.jar")

val gomobileBind =
    tasks.register<Exec>("gomobileBind") {
        description = "Builds the Go networking library in go/ into an AAR with gomobile."
        inputs.files(fileTree(goSources) { include("**/*.go", "go.mod", "go.sum") })
        outputs.file(gomobileAar)
        workingDir(goSources)
        val out = gomobileAar.get().asFile
        doFirst { out.parentFile.mkdirs() }
        commandLine(
            "gomobile",
            "bind",
            "-target=android/arm64,android/amd64",
            "-androidapi",
            "30",
            "-javapkg",
            "com.secrm.gonet.gen",
            "-trimpath",
            "-ldflags=-s -w",
            "-o",
            out.absolutePath,
            ".",
        )
    }

val unpackGomobileClasses =
    tasks.register<Copy>("unpackGomobileClasses") {
        from(zipTree(gomobileAar).matching { include("classes.jar") })
        into(layout.buildDirectory.dir("gomobile/classes"))
        dependsOn(gomobileBind)
    }

/** Extracts the AAR's `jni/<abi>/` libraries into a directory AGP takes as generated jniLibs. */
abstract class UnpackGomobileJni : DefaultTask() {
    @get:InputFile
    abstract val aar: RegularFileProperty

    @get:OutputDirectory
    abstract val jniLibs: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun unpack() {
        files.sync {
            from(archives.zipTree(aar)) {
                include("jni/**")
                eachFile { path = path.removePrefix("jni/") }
            }
            includeEmptyDirs = false
            into(jniLibs)
        }
    }
}

val unpackGomobileJni =
    tasks.register<UnpackGomobileJni>("unpackGomobileJni") {
        aar.set(gomobileAar)
        dependsOn(gomobileBind)
    }

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(unpackGomobileJni, UnpackGomobileJni::jniLibs)
    }
}

dependencies {
    api(files(gomobileClasses).builtBy(unpackGomobileClasses))
}
