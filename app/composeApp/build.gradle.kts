import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "com.saltnote.compose"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":app:nativeBridge"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.ink.authoring)
                implementation(libs.androidx.ink.brush)
                implementation(libs.androidx.ink.strokes)
                implementation(libs.androidx.graphics.core)
                implementation(libs.androidx.input.motionprediction)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.saltnote.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "SaltNote"
            packageVersion = "1.0.0"
            description = "Local-First Dual-Mode Journal & Study Notes with Salt Halo AI and FSRS"
            vendor = "SaltNote Contributors"
        }
    }
}

// Forward MVP documentation convenience tasks (`:app:composeApp:assembleDebug` / `installDebug`)
tasks.register("assembleDebug") {
    group = "saltnote"
    description = "Assemble Android Debug APK via :app:androidApp:assembleDebug"
    dependsOn(":app:androidApp:assembleDebug")
}

tasks.register("installDebug") {
    group = "saltnote"
    description = "Install Android Debug APK onto connected device via :app:androidApp:installDebug"
    dependsOn(":app:androidApp:installDebug")
}
