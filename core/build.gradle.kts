import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM logic shared with the Android app; bytecode level matches Android's.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.sshj)
    api(libs.bouncycastle.prov)
    api(libs.bouncycastle.pkix)
    testImplementation(libs.junit)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
}
