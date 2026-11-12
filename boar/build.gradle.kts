// The tests here run sessions with the proxy's test classes, so the proxy's source sets must exist first.
evaluationDependsOn(":proxy")

dependencies {
    api(project(":proxy"))
    implementation(libs.boar)
    implementation(libs.boar.api)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.yaml)
    runtimeOnly(libs.guava)
    runtimeOnly(libs.gson)

    testImplementation(project.project(":proxy").the<SourceSetContainer>()["test"].output)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Writes the shared block table (BedrockBlockUnion) from the block tables on its classpath, which :blockdata:generateBedrockBlocks writes first.
tasks.register<JavaExec>("generateBedrockBlockUnion") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "ac.oomph.boarium.boar.blocks.BedrockBlockUnion"
    args("src/main/resources/ac/oomph/boarium/boar/blocks/bedrock_blocks.union.bin.gz")
}

tasks.named("processResources") {
    mustRunAfter(":blockdata:generateBedrockBlocks")
}

// Boar's flags log the lines of their details after the first at DEBUG, which the tests check.
tasks.test {
    systemProperty("org.slf4j.simpleLogger.log.Boar", "debug")
}
