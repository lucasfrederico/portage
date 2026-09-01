dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.31-alpha")
    compileOnly("redis.clients:jedis:5.2.0")
    compileOnly("com.zaxxer:HikariCP:6.2.1")

    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand(mapOf("version" to pluginVersion))
    }
}
