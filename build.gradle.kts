plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.englobalmarket"
version = "1.0.3"

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
    // 编译基线取 26.x 里最低的可用坐标（26.1 系列只发布过 26.1.2 的 paper-api），
    // 配合 plugin.yml 的 api-version: 26.1，即可在 26.1.2 / 26.2 / 26.3 上加载。
    // 换成更高版本会引入 26.2+ 才有的 API，导致低版本 26.x 运行时报 NoSuchMethodError。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    // Vault API（编译期，服务器需安装 Vault + 经济插件）
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    // SQLite 驱动（打入 jar）
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    // 单元测试
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // 测试里需要构造 ItemStack 作为商品载荷（编译期依赖在测试运行时也必须可见）
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 在配置期取出版本号，避免在任务执行期访问 project（Gradle 10 起会报错）
val pluginVersion = version.toString()

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
    }
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
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
        archiveFileName.set("EnGlobalMarket-$pluginVersion.jar")
        // 注意：sqlite-jdbc 的 JNI 原生库按原包名查找 org.sqlite.core.NativeDB，不能重定位，否则驱动无法加载
    }
    build {
        dependsOn(shadowJar)
    }
}

