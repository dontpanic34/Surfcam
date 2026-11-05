import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Le cerveau de SurfCam : suivi, détection du surf, pilote du gimbal et du zoom, banc d'évaluation.
// Kotlin pur, sans Android : tout se teste et se rejoue sur ordinateur.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Le banc lit les sessions rangées dans bench/ à la racine du dépôt.
    systemProperty("surfcam.bench", rootProject.file("bench").absolutePath)
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
