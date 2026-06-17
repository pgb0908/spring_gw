plugins {
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "com.example"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/snapshot") }
}

extra["springCloudVersion"] = "2025.1.3-SNAPSHOT"

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.nimbusds:nimbus-jose-jwt:9.37.3")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val packageDist by tasks.registering(Copy::class) {
    dependsOn(tasks.bootJar)

    val distDir = layout.projectDirectory.dir("output/spring-gw-${project.version}")

    // JAR
    from(tasks.bootJar.get().outputs.files)
    // config 디렉토리
    from(layout.projectDirectory.dir("config")) { into("config") }
    // mock 서버 스크립트
    from(layout.projectDirectory.dir("mock")) { into("mock") }
    // 외부에서 덮어쓸 수 있는 application.yml
    from(layout.projectDirectory.file("src/main/resources/application.yml"))

    into(distDir)

    doLast {
        val startScript = distDir.file("start.sh").asFile
        startScript.writeText(
            """#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${'$'}0")" && pwd)"
JAR=${'$'}(ls "${'$'}SCRIPT_DIR"/*.jar | head -1)
PID_FILE="${'$'}SCRIPT_DIR/spring-gw.pid"

if [ -f "${'$'}PID_FILE" ] && kill -0 "${'$'}(cat "${'$'}PID_FILE")" 2>/dev/null; then
    echo "Already running (PID ${'$'}(cat "${'$'}PID_FILE"))"
    exit 1
fi

nohup java --enable-native-access=ALL-UNNAMED ${'$'}{JAVA_OPTS} -jar "${'$'}JAR" "${'$'}@" > "${'$'}SCRIPT_DIR/spring-gw.log" 2>&1 &
echo ${'$'}! > "${'$'}PID_FILE"
echo "Started (PID ${'$'}(cat "${'$'}PID_FILE"))"
"""
        )
        startScript.setExecutable(true)

        val stopScript = distDir.file("stop.sh").asFile
        stopScript.writeText(
            """#!/bin/bash
PID_FILE="$(cd "$(dirname "${'$'}0")" && pwd)/spring-gw.pid"
if [ -f "${'$'}PID_FILE" ]; then
    kill "${'$'}(cat "${'$'}PID_FILE")" && rm -f "${'$'}PID_FILE" && echo "stopped"
else
    echo "PID file not found"
fi
"""
        )
        stopScript.setExecutable(true)

        listOf("start-mock.sh", "stop-mock.sh").forEach { script ->
            distDir.file("mock/$script").asFile.setExecutable(true)
        }

        println("패키지 생성 완료: output/spring-gw-${project.version}/")
    }
}
