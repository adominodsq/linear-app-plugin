import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML

fun properties(key: String) = providers.gradleProperty(key)

fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.apollo3)
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

// Configure project's dependencies
repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

configurations {
    // Create a custom configuration for dependencies that should be bundled
    create("bundle") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
}

dependencies {
    detektPlugins(libs.detekt.formatting)

    // Add to both implementation and bundle
    implementation(libs.apollo.runtime) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    "bundle"(libs.apollo.runtime) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }

    // Test dependencies
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)

    intellijPlatform {
        create(properties("platformType"), properties("platformVersion"))
        bundledPlugins(properties("platformPlugins").map { it.split(',').map(String::trim).filter(String::isNotEmpty) })

        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }
}

// Set the JVM language level used to build the project. Use Java 21 for 2024.2+.
kotlin {
    jvmToolchain(21)
}

// Configure IntelliJ Platform Gradle Plugin 2.0
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
intellijPlatform {
    pluginConfiguration {
        name = properties("pluginName")
        version = properties("pluginVersion")

        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild")
        }

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description =
            providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                with(it.lines()) {
                    if (!containsAll(listOf(start, end))) {
                        throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                    }
                    subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
                }
            }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes =
            properties("pluginVersion").map { pluginVersion ->
                with(changelog) {
                    renderItem(
                        (getOrNull(pluginVersion) ?: getUnreleased())
                            .withHeader(false)
                            .withEmptySections(false),
                        Changelog.OutputType.HTML,
                    )
                }
            }
    }

    signing {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = environment("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Configure gradle-changelog-plugin plugin.
// Read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = properties("pluginRepositoryUrl")
}

// Configure detekt plugin.
// Read more: https://detekt.github.io/detekt/kotlindsl.html
detekt {
    config.setFrom("./detekt-config.yml")
    buildUponDefaultConfig = true
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(false)
        xml.required.set(false)
        txt.required.set(false)
    }
}

ktlint {
    filter {
        exclude { element ->
            // https://github.com/apollographql/apollo-android/issues/2079
            element.file.path.contains("generated/")
        }
    }
}

apollo {
    packageName.set("dev.mayankmkh.intellij.linear.apolloGenerated")
    customTypeMapping.set(
        mapOf(
            "DateTime" to "java.util.Date",
        ),
    )
}

tasks {
    publishPlugin {
        dependsOn("patchChangelog")
    }

    // Bundle dependencies into the composed JAR
    composedJar {
        from(configurations["bundle"].map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    test {
        useJUnitPlatform()
    }
}
