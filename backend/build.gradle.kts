import org.gradle.testing.jacoco.tasks.JacocoReportBase
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("fi.evident.beanstalk") version "0.3.3"
    war
    jacoco
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
}

group = "nl.greaper"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
    maven("https://m2.dv8tion.net/releases")
}

jacoco {
    toolVersion = "0.8.14"
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
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Spring
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Mongo
    implementation("org.litote.kmongo:kmongo:5.2.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging:7.0.7")
    implementation("ch.qos.logback:logback-classic")
    implementation("org.codehaus.janino:janino")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.11.2")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.11.2")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.2")

    // Discord
    implementation("net.dv8tion:JDA:4.4.0_350") {
        exclude("opus-java")
    }

    testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:4.24.0")
    providedRuntime("org.springframework.boot:spring-boot-starter-tomcat")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.JVM_17)
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

    beanstalk {
        s3Endpoint = "s3-eu-west-1.amazonaws.com"
        beanstalkEndpoint = "elasticbeanstalk.eu-west-1.amazonaws.com"

        deployments {
            create("production") {
                file = bootWar
                application = "bnplanner"
                environment = "bnplanner-env"
            }
        }
    }
}
