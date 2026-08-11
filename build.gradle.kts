
plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}



group = "dev.dimo"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    implementation("io.javalin:javalin:7.0.1")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.12.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("paper-web-console")
    archiveClassifier.set("")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
