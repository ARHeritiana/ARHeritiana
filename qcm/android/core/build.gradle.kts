// Cœur de l'application, en Kotlin pur (testable sur un PC sans téléphone) :
// déchiffrement du QR, lecture de la grille sur la photo, barème, export.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    System.getProperty("qcm.xlsx")?.let { systemProperty("qcm.xlsx", it) }
}
