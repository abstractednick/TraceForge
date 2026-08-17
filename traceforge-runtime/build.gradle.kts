plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(11)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to "TraceForge Runtime",
            "Implementation-Version" to project.version
        )
    }
}
