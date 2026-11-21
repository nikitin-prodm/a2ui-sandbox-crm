plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
val catalogDir = rootProject.layout.projectDirectory.dir("../catalog")

android {
    namespace = "dev.a2uicrm.agentic"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        assets.srcDir(catalogDir)
    }
    sourceSets.getByName("test") {
        resources.srcDir(catalogDir)
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // A2UI protocol layer (0.9.1, probe-verified: probe/SUMMARY.txt)
    api("androidx.a2ui:a2ui-engine:1.0.0-alpha02")

    implementation(project(":core:domain"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(project(":core:data"))
}
