import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.18")
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm) // Для Linux
            packageName = "Subless3D"
            packageVersion = "1.0.0"

            linux {
                // Путь к PNG-файлу, который вы подготовили
                iconFile.set(project.file("src/main/resources/app_icon.png"))
            }
        }
    }
}

