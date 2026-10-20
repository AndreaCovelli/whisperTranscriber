buildscript {
    dependencies {
        constraints {
            // AGP 9.4.0 brings older build-only transitives; keep its classpath on patched releases.
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.commons:commons-lang3:3.21.0")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
            // CycloneDX brings Jackson 2.22.2; keep it on a patched release.
            classpath("com.fasterxml.jackson.core:jackson-core:2.22.4")
            classpath("com.fasterxml.jackson.core:jackson-databind:2.22.4")
        }
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.cyclonedx) apply false
}
