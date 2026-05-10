import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("dev.detekt") version("2.0.0-alpha.3")
}

group = "com.tonihacks"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2") // pinned to this v for compatibility with intellj tests
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.tonihacks.doppler-intellij"
        name = "Doppler"
        version = project.version.toString()
        description = "Inject Doppler-managed secrets into JetBrains run configurations and browse/edit them from a tool window."
        ideaVersion {
            sinceBuild = "242"
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

// Run the plugin against non-IDEA IDEs for cross-IDE smoke testing.
intellijPlatformTesting {
    runIde {
        register("runPhpStorm") {
            type = IntelliJPlatformType.PhpStorm
            version = "2024.2"
        }
        register("runWebStorm") {
            type = IntelliJPlatformType.WebStorm
            version = "2025.2"
        }
        register("runRider") {
            type = IntelliJPlatformType.Rider
            version = "2025.2"
        }
        register("runPyCharm") {
            type = IntelliJPlatformType.PyCharm
            version = "2025.2"
        }
    }
}
