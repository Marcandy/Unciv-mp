import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin")
}

sourceSets {
    main {
        java.srcDir("src/")
    }
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
