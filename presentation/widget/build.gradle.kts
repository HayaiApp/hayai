import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("yokai.android.library")
    id("yokai.android.library.compose")
}

android {
    namespace = "yokai.presentation.widget"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:main"))
    implementation(project(":data"))
    implementation(project(":domain"))
    implementation(project(":i18n"))
    implementation(project(":presentation:core"))
    implementation(project(":source:api"))  // Access to SManga

    implementation(androidx.glance.appwidget)

    implementation(platform(libs.coil3.bom))
    implementation(libs.coil3)
}

tasks {
    withType<KotlinCompile> {
        compilerOptions.freeCompilerArgs.addAll(
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
        )
    }
}
