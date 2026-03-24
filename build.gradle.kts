plugins {
    java
    `jvm-test-suite`
}

repositories {
    mavenCentral()
    mavenLocal()
}

// Default to Vert.x 5; override with -PvertxVersion=4.5.25
val vertxVersion = project.findProperty("vertxVersion")?.toString() ?: "5.0.8"

dependencies {
    implementation("io.vertx:vertx-core:$vertxVersion")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

testing.suites.withType<JvmTestSuite>().configureEach {
    useJUnitJupiter()
    targets.all { testTask.configure {
        // Show test output for debugging
        testLogging {
            events("failed")
            showStandardStreams = true
        }
        // Allow long-running flake tests
        systemProperty("junit.jupiter.execution.timeout.default", "10m")
    } }
}
