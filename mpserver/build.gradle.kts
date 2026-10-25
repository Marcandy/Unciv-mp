import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin")
}

sourceSets {
    main {
        java.srcDir("src/")
    }
    test {
        java.srcDir("test/")
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    workingDir = file("../android/assets") // the engine loads rulesets relative to the assets folder
    jvmArgs("-Xmx2g", "-Djava.awt.headless=true")
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.register<JavaExec>("run") {
    description = "Run the authoritative game + agent server"
    dependsOn(tasks.getByName("classes"))
    mainClass.set("com.unciv.mpserver.MainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = file("../android/assets")
    jvmArgs("-Xmx2g", "-Djava.awt.headless=true")
    args = (project.findProperty("serverArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}
