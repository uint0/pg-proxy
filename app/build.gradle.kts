plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.0-Beta1"

    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("dev.uint0.trickroom.pg.proxy.AppKt")
}
