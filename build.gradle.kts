plugins {
    java
}

repositories {
    // PaperMC : fournit l'API de Velocity
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
    // EterVelocityLib : compilé depuis GitHub
    maven("https://jitpack.io")
    // Repli : EterVelocityLib publié sur cette machine (`gradlew publishToMavenLocal`), pour tester avant de pousser
    mavenLocal()
}

dependencies {
    // Fournit aussi Adventure/MiniMessage, SnakeYAML, Guice et SLF4J, présents sur le proxy
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    // Génère velocity-plugin.json à partir de l'annotation @Plugin
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0")
    // Socle commun du proxy : config, langues, palette, orchestrateur (plugin EterVelocityLib installé sur le proxy)
    compileOnly("com.github.Eternom:EterVelocityLib:1.2.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

// Après chaque build, copie le jar dans <eterPluginsDir>/velocity et y supprime l'ancienne version de ce plugin.
// eterPluginsDir se règle dans ~/.gradle/gradle.properties (propre à ta machine) : sans lui, rien n'est copié.
val deployPlugin by tasks.registering(Copy::class) {
    description = "Copie le jar dans le dossier de plugins local (eterPluginsDir)"
    // Variables locales à la tâche : le cache de configuration de Gradle refuse les variables du script
    val eterPluginsDir = providers.gradleProperty("eterPluginsDir")
    val enabled = eterPluginsDir.isPresent
    onlyIf { enabled }
    val jarName = tasks.jar.flatMap { it.archiveBaseName }
    from(tasks.jar)
    into(eterPluginsDir.map { "$it/velocity" }.orElse(layout.buildDirectory.dir("deploy").map { it.asFile.path }))
    doFirst {
        destinationDir.listFiles { file -> file.name.startsWith(jarName.get() + "-") && file.name.endsWith(".jar") }
            ?.forEach { it.delete() }
    }
}
tasks.build { finalizedBy(deployPlugin) }
