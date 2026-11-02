// Builds boar's block table from a Geyser standalone jar. Run it by hand: ./gradlew :blockdata:run --args="<Geyser.jar> <output file>"
plugins {
    application
}

application {
    mainClass = "ac.oomph.boarium.blockdata.BlockTableGenerator"
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
