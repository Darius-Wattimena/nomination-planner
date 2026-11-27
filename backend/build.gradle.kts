import org.gradle.testing.jacoco.tasks.JacocoReportBase

plugins {
    id("org.springframework.boot") version "4.2.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    war
    jacoco
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
}

group = "nl.greaper"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

jacoco {
    toolVersion = "0.8.16"
}

ktlint {
    version.set("1.8.0")
}

// ktlint runs on the Kotlin compiler it was built with, not the one of this project
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.2.21")
        }
    }
}

dependencies {
    val springBoot = platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    implementation(springBoot)
    testImplementation(springBoot)
    providedRuntime(springBoot)

    // Spring
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Mongo
    implementation("org.mongodb:mongodb-driver-sync")
    implementation("org.mongodb:mongodb-spring-session:4.0.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.7")

    // Discord
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "opus-java")
    }

    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:5.0.0")
    // Only Tomcat itself, which Beanstalk provides. The whole starter would also move spring-web and spring-boot out of the war.
    providedRuntime("org.springframework.boot:spring-boot-starter-tomcat-runtime")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }

    // Classes a test is meaningless for
    val coverageExclusions =
        listOf(
            // Only the main function starting the server, tests start the application themselves
            "nl/greaper/bnplanner/ApplicationKt.class",
        )

    withType<JacocoReportBase> {
        dependsOn(test)
        classDirectories.setFrom(
            sourceSets.main
                .get()
                .output.asFileTree
                .matching { exclude(coverageExclusions) },
        )
    }

    jacocoTestReport {
        reports {
            xml.required.set(true)
        }
    }

    jacocoTestCoverageVerification {
        violationRules {
            rule {
                listOf("LINE", "BRANCH").forEach {
                    limit {
                        counter = it
                        minimum = BigDecimal.ONE
                    }
                }
            }
        }
    }

    check {
        dependsOn(jacocoTestReport, jacocoTestCoverageVerification)
    }

    // Only the executable war is deployed, under a name the deployment can rely on
    bootWar {
        archiveFileName.set("bnplanner.war")
    }

    // What Elastic Beanstalk gets: the war as the root of Tomcat, and the setup of the instance (https with Let's Encrypt)
    val beanstalkBundle =
        register<Zip>("beanstalkBundle") {
            group = "build"
            description = "Bundles the war with the setup of the instance for Elastic Beanstalk"
            archiveFileName.set("bnplanner.zip")
            destinationDirectory.set(layout.buildDirectory.dir("libs"))

            from(bootWar) { rename { "ROOT.war" } }
            from(".platform") { into(".platform") }
            // The certificate is also checked when only the settings change
            from(".platform/hooks/prebuild") { into(".platform/confighooks/prebuild") }
            filesMatching("**/*.sh") { permissions { unix("rwxr-xr-x") } }
        }

    assemble {
        dependsOn(beanstalkBundle)
    }

    war {
        enabled = false
    }

    jar {
        enabled = false
    }
}
