import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "DanyFyShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.2")
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "app.danyfy.shared.resources"
    publicResClass = false
}

val desktopCompilation = kotlin.jvm("desktop").compilations.getByName("main")

tasks.register<JavaExec>("renderScreenshots") {
    group = "verification"
    description = "Renders Classic UI screenshots for all primary screens on desktop Skia."
    mainClass.set("app.danyfy.shared.ScreenshotsKt")
    classpath(desktopCompilation.runtimeDependencyFiles, desktopCompilation.output.allOutputs)
}
