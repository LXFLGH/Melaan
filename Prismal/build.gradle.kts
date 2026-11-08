import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val compileSdkOverride = providers.gradleProperty("melaanCompileSdk").orNull?.toIntOrNull()

android {
    namespace = "com.styropyr0.prismal"
    compileSdk = compileSdkOverride ?: 37

    defaultConfig { minSdk = 25 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api("androidx.compose.foundation:foundation")
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api("androidx.compose.runtime:runtime")
    implementation(libs.androidx.core.ktx)
    implementation("androidx.startup:startup-runtime:1.2.0")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}
