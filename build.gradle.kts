plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.englobalmarket"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    // Paper API（编译期，服务器自带）
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    // Vault API（编译期，服务器需安装 Vault + 经济插件）
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    // SQLite 驱动（打入 jar）
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    // 单元测试
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
    }
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
        }
    }
    // 瘦 jar 与 shadowJar 默认同名，会互相覆盖（单独执行 `gradlew jar` 后部署会丢 SQLite 驱动），
    // 因此给瘦 jar 加后缀区分，可部署的只有 EnGlobalMarket-<版本>.jar
    jar {
        archiveClassifier.set("thin")
    }
    shadowJar {
        archiveFileName.set("EnGlobalMarket-${project.version}.jar")
        // 注意：sqlite-jdbc 的 JNI 原生库按原包名查找 org.sqlite.core.NativeDB，不能重定位，否则驱动无法加载
    }
    build {
        dependsOn(shadowJar)
    }
}

