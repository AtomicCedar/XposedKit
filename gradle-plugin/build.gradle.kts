plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.build.tools)
    implementation(libs.devtools.ksp)
}

gradlePlugin {
    plugins {
        create("xposedkit") {
            displayName = "XposedKit"
            id = "cc.meteormc.xposedkit.plugin"
            description = "Xposed development toolkit"
            implementationClass = "cc.meteormc.xposedkit.plugin.XposedKitPlugin"
        }
    }
}