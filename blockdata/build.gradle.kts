// Builds boar's block tables. Run them by hand:
// ./gradlew :blockdata:run --args="<Geyser.jar> <output file>" for the Java table, from a Geyser standalone jar;
// ./gradlew :blockdata:generateBedrockBlocks for the Bedrock table, from the Bedrock data in data/bedrock.
plugins {
    application
}

dependencies {
    implementation(libs.nbt)
    implementation(libs.gson)
}

application {
    mainClass = "ac.oomph.boarium.blockdata.BlockTableGenerator"
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.register<JavaExec>("generateBedrockBlocks") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "ac.oomph.boarium.blockdata.BedrockBlockGenerator"
    workingDir = rootDir
    args("blockdata/data/bedrock/1.26.30", "boar/src/main/resources/ac/oomph/boarium/boar/blocks/bedrock_blocks.26_30.bin.gz")
}

tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
