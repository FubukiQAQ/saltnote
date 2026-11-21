import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

val rustRootDir = rootProject.file("rust")
val generatedUniffiDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
val generatedDesktopNativeResDir = layout.buildDirectory.dir("generated/rustDesktopRes")
val androidJniLibsDir = rootProject.file("app/androidApp/src/main/jniLibs")

fun resolveCargoExecutable(): String {
    val home = System.getProperty("user.home")
    val exeName = if (OperatingSystem.current().isWindows) "cargo.exe" else "cargo"
    val candidate = file("$home/.cargo/bin/$exeName")
    return if (candidate.exists()) candidate.absolutePath else exeName
}

fun resolvePathWithCargo(): String {
    val home = System.getProperty("user.home")
    val cargoBin = file("$home/.cargo/bin").absolutePath
    val sep = File.pathSeparator
    val existing = System.getenv("PATH") ?: ""
    return if (existing.contains(cargoBin)) existing else "$cargoBin$sep$existing"
}

fun resolveAndroidNdkHome(): String? {
    System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() && file(it).isDirectory }?.let { return it }
    val candidates = mutableListOf<String>()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        val props = Properties()
        localPropsFile.inputStream().use { props.load(it) }
        props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { candidates += it }
    }
    System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { candidates += it }
    System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { candidates += it }
    for (sdkDir in candidates) {
        val ndkRoot = file("$sdkDir/ndk")
        if (ndkRoot.isDirectory) {
            val chosen = ndkRoot.listFiles()
                ?.filter { it.isDirectory && file("${it.absolutePath}/source.properties").exists() }
                ?.maxByOrNull { it.name }
            if (chosen != null) return chosen.absolutePath
        }
    }
    return null
}

fun desktopNativeLibFilename(): String {
    val os = OperatingSystem.current()
    return when {
        os.isWindows -> "saltnote_ffi.dll"
        os.isMacOsX -> "libsaltnote_ffi.dylib"
        else -> "libsaltnote_ffi.so"
    }
}

fun desktopJnaArchFolder(): String {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm64 = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.isWindows -> if (isArm64) "win32-aarch64" else "win32-x86-64"
        os.isMacOsX -> if (isArm64) "darwin-aarch64" else "darwin-x86-64"
        else -> if (isArm64) "linux-aarch64" else "linux-x86-64"
    }
}

val buildRustDesktop by tasks.registering(Exec::class) {
    group = "saltnote"
    description = "Compile saltnote_ffi Rust library for host desktop platform"
    workingDir = rustRootDir
    environment("PATH", resolvePathWithCargo())
    commandLine(resolveCargoExecutable(), "build", "--release", "-p", "salt-ffi")

    doLast {
        val libName = desktopNativeLibFilename()
        val builtLib = file("$rustRootDir/target/release/$libName")
        require(builtLib.exists()) { "Expected compiled Rust library at ${builtLib.absolutePath}" }
        val outRoot = generatedDesktopNativeResDir.get().asFile
        val jnaArchDir = file("${outRoot.absolutePath}/${desktopJnaArchFolder()}")
        jnaArchDir.mkdirs()
        builtLib.copyTo(file("${jnaArchDir.absolutePath}/$libName"), overwrite = true)
        builtLib.copyTo(file("${outRoot.absolutePath}/$libName"), overwrite = true)
    }
}

val generateUniFFIBindings by tasks.registering(Exec::class) {
    group = "saltnote"
    description = "Generate Kotlin UniFFI bindings using in-repo rust/uniffi-bindgen"
    dependsOn(buildRustDesktop)
    workingDir = rustRootDir
    environment("PATH", resolvePathWithCargo())

    val libPath = file("$rustRootDir/target/release/${desktopNativeLibFilename()}").absolutePath
    val outDir = generatedUniffiDir.get().asFile
    doFirst {
        outDir.mkdirs()
    }
    commandLine(
        resolveCargoExecutable(),
        "run",
        "--release",
        "-p",
        "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        libPath,
        "--language",
        "kotlin",
        "--out-dir",
        outDir.absolutePath,
    )
}

val buildRustAndroidArm64 by tasks.registering(Exec::class) {
    group = "saltnote"
    description = "Cross-compile saltnote_ffi for Android ARM64 (arm64-v8a) via cargo-ndk"
    workingDir = rustRootDir
    environment("PATH", resolvePathWithCargo())
    resolveAndroidNdkHome()?.let { environment("ANDROID_NDK_HOME", it) }

    doFirst {
        androidJniLibsDir.mkdirs()
    }
    commandLine(
        resolveCargoExecutable(),
        "ndk",
        "-t",
        "arm64-v8a",
        "--platform",
        "26",
        "-o",
        androidJniLibsDir.absolutePath,
        "build",
        "--release",
        "-p",
        "salt-ffi",
    )
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "com.saltnote.bridge"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }

        val desktopMain by getting {
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
            kotlin.srcDir(generatedUniffiDir)
            resources.srcDir(generatedDesktopNativeResDir)
            dependencies {
                api(libs.jna)
                api(libs.jna.platform)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }

        val androidMain by getting {
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
            kotlin.srcDir(generatedUniffiDir)
            dependencies {
                api("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
                implementation(libs.androidx.core.ktx)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.matching {
    it.name.contains("compileKotlin", ignoreCase = true) ||
        it.name.contains("processResources", ignoreCase = true)
}.configureEach {
    dependsOn(generateUniFFIBindings)
}
