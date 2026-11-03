// Builds boar's Bedrock block table from the Bedrock data in data/bedrock. Run it by hand: ./gradlew :blockdata:generateBedrockBlocks
dependencies {
    implementation(libs.nbt)
    implementation(libs.gson)
}

tasks.register<JavaExec>("generateBedrockBlocks") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "ac.oomph.boarium.blockdata.BedrockBlockGenerator"
    workingDir = rootDir
    args("blockdata/data/bedrock/1.26.30", "boar/src/main/resources/ac/oomph/boarium/boar/blocks/bedrock_blocks.26_30.bin.gz")
}
