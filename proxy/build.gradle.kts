dependencies {
    api(libs.protocol)
    // The protocol library asks for these as moving snapshots, so each is held to one build.
    api(libs.protocol.codec) {
        version { strictly(libs.versions.protocol.get()) }
    }
    api(libs.protocol.common) {
        version { strictly(libs.versions.protocol.get()) }
    }
    // Held to one build of Cloudburst's network modules, which replaces the RakNet 1.1.0.CR4-SNAPSHOT that the protocol library asks for.
    api(libs.raknet) {
        version { strictly(libs.versions.network.get()) }
    }
    api(libs.nethernet) {
        version { strictly(libs.versions.network.get()) }
    }
    api(libs.network.common) {
        version { strictly(libs.versions.network.get()) }
    }
    // The natives ask for libdatachannel-java as a moving snapshot, so it is held to the build the NetherNet transport asks for.
    implementation(libs.libdatachannel) {
        version { strictly(libs.versions.libdatachannel.get()) }
    }
    // The native libraries for every platform, which libdatachannel-java does not bring.
    runtimeOnly(libs.libdatachannel.natives) {
        version { strictly(libs.versions.libdatachannel.get()) }
    }
    implementation(libs.aircompressor)
    implementation(libs.gson)
    implementation(libs.snakeyaml)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
