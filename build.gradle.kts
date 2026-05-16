import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("org.jetbrains.changelog") version "2.2.1"
    id("dev.detekt") version("2.0.0-alpha.3")
}

group = "com.tonihacks"
version = providers.exec {
    commandLine("bash", "-c", "git describe --tags --abbrev=0 2>/dev/null || echo 'v0.0.0-dev'")
}.standardOutput.asText.get().trim().removePrefix("v")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves all configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "Run with --write-locks (e.g. ./gradlew resolveAndLockAll --write-locks)"
        }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

dependencies {
    intellijPlatform {
        // IDEA Ultimate base: bundles Java, Gradle, JavaScript, and NodeJS plugins —
        // covers four of the five compile classpaths we need. The Python plugin (Pythonid)
        // is NOT bundled in IDEA Ultimate, so we pull it from the JetBrains Marketplace
        // as a third-party dependency. The compiled plugin still installs and runs on
        // Community, WebStorm, PyCharm, Rider, etc. — base only affects compile classpath.
        intellijIdeaUltimate("2026.1")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("com.intellij.java")
        bundledPlugin("JavaScript")
        // NodeJS plugin (bundled in IDEA Ultimate, WebStorm, PyCharm Pro). Not strictly
        // required for the injector — the extension base lives in JavaScript — but tests
        // need NodeJsRunConfigurationType to construct concrete Node run configs.
        bundledPlugin("NodeJS")
        // Python Community Edition plugin from JetBrains Marketplace (ID 7322). Provides
        // the `PythonRunConfigurationExtension` base + `AbstractPythonRunConfiguration`
        // we need. Pinned to 261.22158.277 — the marketplace build whose `since` build
        // matches IU-2026.1 GA (IU-261.22158.277). Newer 261.x PythonCore artifacts
        // (e.g. 261.24374.66) require a later 261.x sub-build than the IU GA ships, so
        // they fail to load with "requires IDE build 261.24374 or newer" in the sandbox.
        plugin("PythonCore", "261.22158.277")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4") // matches IntelliJ Platform 2026.1 test framework (TestFixtureExtension uses ExtensionContext.getEnclosingTestClasses, added in 5.12)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // junit 3/4 TestCase is required at runtime by com.intellij.tests.JUnit5TestSessionListener
    // (the IntelliJ Platform 2026.1 test framework's session listener loads it eagerly).
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.tonihacks.doppler-intellij"
        name = "Doppler"
        version = project.version.toString()
        description = "Inject Doppler-managed secrets into JetBrains run configurations and browse/edit them from a tool window."
        ideaVersion {
            sinceBuild = "261.22158"
        }
        changeNotes = providers.provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getLatest()),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
    // Pin verifier to IU-2026.1 (matches the PythonCore 261.22158.277 pin above).
    // Without this, the default `recommended()` IDE list also picks IU-2026.2,
    // where the 261.x PythonCore artifact does not resolve and the optional
    // `com.intellij.modules.python` dep produces a hard COMPATIBILITY ERROR.
    // Bumping verification to 2026.2 requires bumping the PythonCore pin to a
    // 262-compatible build (e.g. 262.4852.50) — do both together.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
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
            version = "2026.1"
            plugins {
                // PythonCore is not relevant in PhpStorm and requires org.toml.lang
                // which is not bundled here; disable to prevent a broken-dependency balloon.
                disablePlugin("PythonCore")
            }
        }
        register("runWebStorm") {
            type = IntelliJPlatformType.WebStorm
            version = "2026.1"
            plugins {
                // PythonCore is not relevant in WebStorm and requires org.toml.lang
                // which is not bundled here; disable to prevent a broken-dependency balloon.
                disablePlugin("PythonCore")
            }
        }
        register("runRider") {
            type = IntelliJPlatformType.Rider
            version = "2026.1"
            plugins {
                // PythonCore is not relevant in Rider and requires org.toml.lang
                // which is not bundled here; disable to prevent a broken-dependency balloon.
                disablePlugin("PythonCore")
            }
        }
        register("runPyCharm") {
            type = IntelliJPlatformType.PyCharm
            version = "2026.1"
        }
    }
}
