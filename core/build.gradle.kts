plugins {
    `maven-publish`
    alias(libs.plugins.android.library)
}

android {
    namespace = "cc.meteormc.xposedkit"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    compileOnly(projects.hiddenApi)
    implementation(projects.native)

    compileOnly(libs.annotation)
    api(libs.hidden.api.bypass)

    compileOnly(libs.xposed.api)
    compileOnly(libs.lsposed.api)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "xposedkit-core"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}