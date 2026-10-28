// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

val projectGroup = "cc.meteormc"
val projectVersion = "1.0.0"

val minSdkVersion = 24
val sdkVersion = 37
val minorSdkVersion = 1

subprojects {
    group = projectGroup
    version = projectVersion

    plugins.withType<com.android.build.gradle.LibraryPlugin> {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            compileSdk {
                version = release(sdkVersion) {
                    if (minorSdkVersion > 0) {
                        minorApiLevel = minorSdkVersion
                    }
                }
            }

            defaultConfig {
                minSdk = minSdkVersion
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }

            publishing {
                singleVariant("release") {
                    withJavadocJar()
                    withSourcesJar()
                }
            }
        }
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(11)

            compilerOptions {
                jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
            jvmToolchain(11)

            compilerOptions {
                jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
            }
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication> {
                pom {
                    developers {
                        developer {
                            name.set("Meteor23333")
                            url.set("https://meteormc.cc")
                        }
                    }
                }
            }
        }
    }
}

tasks.register<Delete>("clean") {
    val layout = rootProject.layout
    delete(layout.buildDirectory)
    delete(layout.projectDirectory.dir(".kotlin"))
}