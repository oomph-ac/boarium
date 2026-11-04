dependencies {
    api(libs.protocol)
    // The protocol library asks for these as moving snapshots, so each is held to one build.
    api(libs.protocol.codec) {
        version { strictly(libs.versions.protocol.get()) }
    }
    api(libs.protocol.common) {
        version { strictly(libs.versions.protocol.get()) }
    }
    api(libs.raknet) {
        version { strictly(libs.versions.raknet.get()) }
    }
    api(libs.raknet.common) {
        version { strictly(libs.versions.raknet.get()) }
    }
    implementation(libs.aircompressor)
    implementation(libs.snakeyaml)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
