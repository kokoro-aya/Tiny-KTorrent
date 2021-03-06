import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.20"
}

group = "org.ironica"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        setUrl("https://jitpack.io")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.5.21")

    implementation("org.slf4j:slf4j-api:1.7.32")
    implementation("ch.qos.logback:logback-classic:1.2.5")
    implementation("ch.qos.logback:logback-core:1.2.5")

    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.1")
    implementation("io.ktor:ktor-client-core:1.6.1")
    implementation("io.ktor:ktor-client-cio:1.6.1")
    implementation("org.junit.jupiter:junit-jupiter:5.7.0")
    testImplementation("org.junit.platform:junit-platform-commons:1.5.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "11"
}