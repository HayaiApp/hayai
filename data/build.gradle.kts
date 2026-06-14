import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(kotlinx.plugins.serialization)
    alias(libs.plugins.sqldelight)
}

tasks {
    withType<KotlinCompile> {
        compilerOptions.freeCompilerArgs.addAll(
            "-Xwarning-level=DEPRECATION:disabled",
            "-Xwarning-level=OVERRIDE_DEPRECATION:disabled",
        )
    }
}

kotlin {
    android {
        namespace = "yokai.data"
        compileSdk = AndroidConfig.COMPILE_SDK
        minSdk = AndroidConfig.MIN_SDK
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(AndroidConfig.JavaVersion.toString()))
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":domain"))
                api(libs.bundles.db)
            }
        }
        val androidMain by getting {
            dependencies {
                api(libs.bundles.db.android)
                implementation(project(":source:api"))
            }
        }
    }
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("yokai.data")
            dialect(libs.sqldelight.dialects.sql)
            schemaOutputDirectory.set(project.file("./src/commonMain/sqldelight"))
        }
    }
}
