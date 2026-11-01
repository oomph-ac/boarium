dependencies {
    api(libs.protocol)
    implementation(libs.aircompressor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
