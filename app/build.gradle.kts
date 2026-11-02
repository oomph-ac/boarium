// The runnable boarium: the proxy, with Boar when the config turns it on.
plugins {
    application
}

// The smoke test joins with the proxy's test client and fake server, so the proxy's source sets must exist first.
evaluationDependsOn(":proxy")

application {
    mainClass = "ac.oomph.boarium.app.Main"
    applicationName = "boarium"
}

dependencies {
    implementation(project(":boar"))
    implementation(libs.slf4j.api)

    testImplementation(project.project(":proxy").the<SourceSetContainer>()["test"].output)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val installDist = tasks.named<Sync>("installDist")

// The smoke test runs the installed start script.
tasks.test {
    dependsOn(installDist)
    systemProperty("boarium.install", installDist.get().destinationDir.absolutePath)
}
