// Builds boar's Bedrock block tables from the Bedrock data in data/bedrock. Run it by hand: ./gradlew :blockdata:generateBedrockBlocks
dependencies {
    implementation(libs.nbt)
    implementation(libs.gson)
}

// Each table: the data folder, the version in the resource name, the protocol, and the BedrockData folder the data came from (see each folder's README)
val tables = listOf(
    listOf("1.26.0", "26_0", "924", "1.26.0.2"),
    listOf("1.26.10", "26_10", "944", "1.26.12.2"),
    listOf("1.26.20", "26_20", "975", "1.26.20.4"),
    listOf("1.26.30", "26_30", "1001", "1.26.33.1"),
    listOf("1.26.40", "26_40", "2168", "1.26.40.8"),
    listOf("1.26.50", "26_50", "2193", "1.26.50.5"),
    listOf("1.26.60", "26_60", "2225", "1.26.60.6"))

tasks.register<JavaExec>("generateBedrockBlocks") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "ac.oomph.boarium.blockdata.BedrockBlockGenerator"
    workingDir = rootDir
    args(tables.flatMap { (folder, version, protocol, source) ->
        listOf("blockdata/data/bedrock/$folder", "boar/src/main/resources/ac/oomph/boarium/boar/blocks/bedrock_blocks.$version.bin.gz", protocol, source)
    })
}
