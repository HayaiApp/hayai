import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import yokai.build.generatedBuildDir

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.moko)
}

kotlin {
    android {
        namespace = "yokai.i18n"
        compileSdk = AndroidConfig.COMPILE_SDK
        minSdk = AndroidConfig.MIN_SDK
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(AndroidConfig.JavaVersion.toString()))
        }
        androidResources {
            enable = true
        }
    }
//    iosX64()
//    iosArm64()
//    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.moko.resources)
                api(libs.moko.resources.compose)
            }
        }
        androidMain {
        }
//        iosMain {
//        }
    }
}

val generatedAndroidResourceDir = generatedBuildDir.resolve("android/res")

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addStaticSourceDirectory("src/commonMain/resources")
        variant.sources.res?.addStaticSourceDirectory(generatedAndroidResourceDir.absolutePath)
    }
}

multiplatformResources {
    resourcesPackage.set("yokai.i18n")
}

tasks {
   val localesConfigTask = project.registerLocalesConfigTask(generatedAndroidResourceDir)
   matching { it.name.contains("Resources") }.configureEach {
       dependsOn(localesConfigTask)
   }

    withType<KotlinCompile> {
        compilerOptions.freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
        )
    }
}
