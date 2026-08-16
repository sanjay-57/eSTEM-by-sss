plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sss.estem.audio"
    compileSdk = 35

    defaultConfig {
        minSdk = 27

        externalNativeBuild {
            cmake {
                // -O3 everywhere: the render callback is the hot path and debug builds of it
                // will underrun on real hardware.
                cppFlags += listOf("-std=c++17", "-O3", "-Wall")
                // Oboe's prefab package ships against the shared STL and refuses to link
                // otherwise.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // The beat detector logs what it found, and android.util.Log is a stub that throws in a
        // plain JVM test. The alternative is threading a logger through pure DSP to make it
        // testable, which is a worse trade than this one line.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.oboe)

    testImplementation(libs.junit)
}
