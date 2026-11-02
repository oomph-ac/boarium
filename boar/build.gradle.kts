// The tests here run sessions with the proxy's test classes, so the proxy's source sets must exist first.
evaluationDependsOn(":proxy")

dependencies {
    api(project(":proxy"))
    implementation(libs.boar)
    // Boar's common module asks for the api module's moving dev2-SNAPSHOT.
    implementation(libs.boar.api) {
        version { strictly(libs.versions.boar.get()) }
    }
    implementation(libs.slf4j.api)
    runtimeOnly(libs.guava)
    runtimeOnly(libs.gson)

    testImplementation(project.project(":proxy").the<SourceSetContainer>()["test"].output)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
