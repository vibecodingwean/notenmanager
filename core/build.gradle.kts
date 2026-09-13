plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    jvmToolchain(17)
    iosArm64 {
        binaries.framework {
            baseName = "StreberAlarmCore"
            isStatic = true
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "StreberAlarmCore"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation(kotlin("test-junit"))
        }
    }
}

// Keep the established verification command while also exposing the normal KMP jvmTest task.
tasks.register("test") { dependsOn("jvmTest") }
