plugins {
    `maven-publish`
    alias(libs.plugins.android.library)
}

android {
    namespace = "cc.meteormc.xposedkit.nativelib"
    ndkVersion = com.android.SdkConstants.NDK_DEFAULT_VERSION

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_C_FLAGS_RELEASE=-Os",
                        "-DCMAKE_CXX_FLAGS_RELEASE=-Os"
                    )
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    compileOnly(libs.annotation)
}

tasks.named<Delete>("clean") {
    val projectDirectory = layout.projectDirectory
    delete(
        projectDirectory.dir(".cxx"),
        projectDirectory.dir(".externalNativeBuild")
    )
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "xposedkit-native"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}