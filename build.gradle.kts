plugins {
    base
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "io.traceforge"
    version = providers.gradleProperty("traceforgeVersion").get()
}

tasks.wrapper {
    gradleVersion = "8.10.2"
    distributionType = Wrapper.DistributionType.BIN
}
