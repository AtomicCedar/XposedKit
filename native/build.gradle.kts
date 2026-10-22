plugins {
    `maven-publish`
    alias(libs.plugins.android.library)
}

android {
    namespace = "cc.meteormc.xposedkit.nativelib"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags("")
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