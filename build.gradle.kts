plugins {
    java
    application
}

group = "com.autoideator"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // CLI
    implementation("info.picocli:picocli:4.7.5")
    annotationProcessor("info.picocli:picocli-codegen:4.7.5")

    // Configuration (HOCON)
    implementation("com.typesafe:config:1.4.3")

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.slf4j:slf4j-api:2.0.9")

    // Concurrency utilities
    implementation("org.jctools:jctools-core:4.0.2")

    // Web framework for UI (includes embedded Jetty)
    implementation("io.javalin:javalin:6.1.3")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")
    testImplementation("org.assertj:assertj-core:3.25.1")
}

application {
    mainClass.set("com.autoideator.AutoIdeatorApplication")
    // Ensure the installDist-generated launcher (build/install/autoideator/bin/autoideator)
    // passes --enable-preview to the JVM. Without this, the launcher fails immediately
    // because the bytecode is compiled with --enable-preview and the JVM refuses to load
    // preview-flagged classes without the matching runtime flag.
    applicationDefaultJvmArgs = listOf("--enable-preview")
}

// Dashboard application task
tasks.register<JavaExec>("runDashboard") {
    group = "application"
    description = "Runs the AutoIdeator web dashboard"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.autoideator.DashboardApplication")
    jvmArgs("--enable-preview")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
    maxHeapSize = "2g"
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "com.autoideator.AutoIdeatorApplication"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
}

// Create a fat/uber jar task
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Creates a fat JAR with all dependencies"

    manifest {
        attributes["Main-Class"] = "com.autoideator.AutoIdeatorApplication"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    archiveBaseName.set("autoideator")
    archiveClassifier.set("all")
    archiveVersion.set("")
}
