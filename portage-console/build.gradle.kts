plugins {
    application
}

dependencies {
    implementation("io.javalin:javalin:6.7.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("redis.clients:jedis:5.2.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.2")
    implementation("com.google.code.gson:gson:2.11.0")
}

application {
    mainClass = "dev.lucasfrederico.portage.console.Console"
}
