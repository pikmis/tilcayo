import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Игровая логика подключается напрямую из репозитория tilcayo — единый источник правды.
// Локальная разработка: соседняя папка ../tilcayo. Публикация в форке игры: ../app (порт лежит в pc/).
// MainActivity.kt исключён: он завязан на Android Activity, его роль играет DesktopMain.
val androidSrcDir: File? = listOf(
    File(project.projectDir, "../app/src/main/java"),
    File(project.projectDir, "../tilcayo/app/src/main/java"),
).firstOrNull { it.isDirectory }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets["main"].kotlin {
        if (androidSrcDir != null) srcDir(androidSrcDir)
        exclude("**/MainActivity.kt")
    }
}

application {
    mainClass.set("com.tilcayo.pc.DesktopMainKt")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Один запускаемый jar: java -jar tilcayo.jar
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Собирает tilcayo.jar со всеми зависимостями (kotlin-stdlib) внутри."
    archiveFileName.set("tilcayo.jar")
    manifest {
        attributes["Main-Class"] = "com.tilcayo.pc.DesktopMainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
}

// Автономный Windows-.exe (со встроенной JVM) через jpackage: build/dist/Tilcayo/Tilcayo.exe
// Запуск: .\gradlew.bat packageExe   (требуется JDK 17+, совпадающий с JAVA_HOME сборки)
tasks.register("packageExe") {
    group = "distribution"
    description = "Собирает автономный Tilcayo.exe (образ приложения со встроенной JVM)."
    dependsOn("fatJar")

    val inputDir = layout.buildDirectory.dir("jpackage-input")
    val jarProvider = tasks.named<Jar>("fatJar").flatMap { it.archiveFile }
    val iconFile = layout.projectDirectory.file("packaging/tilcayo.ico").asFile
    val distDir = layout.projectDirectory.dir("dist")

    doLast {
        val javaHome = System.getProperty("java.home")
        val exeSuffix = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        val jpackage = File(javaHome, "bin/jpackage$exeSuffix")
        if (!jpackage.exists()) {
            throw GradleException("jpackage не найден: ${jpackage.absolutePath}. Нужен JDK 17+.")
        }

        val input = inputDir.get().asFile
        input.mkdirs()
        input.listFiles()?.forEach { it.delete() }

        val jar = jarProvider.get().asFile
        jar.copyTo(File(input, "tilcayo.jar"), overwrite = true)

        val dist = distDir.asFile
        if (dist.exists()) dist.deleteRecursively()
        dist.mkdirs()

        val args = mutableListOf(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", "Tilcayo",
            "--input", input.absolutePath,
            "--main-jar", "tilcayo.jar",
            "--main-class", "com.tilcayo.pc.DesktopMainKt",
            "--dest", dist.absolutePath,
            "--app-version", "1.0.0",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--vendor", "PC port",
            "--description", "PC port of Tolsty Tilcayo",
            "--add-modules", "java.base,java.desktop,java.xml,java.datatransfer,java.logging",
        )
        if (iconFile.exists()) {
            args += listOf("--icon", iconFile.absolutePath)
        }

        logger.lifecycle("jpackage: ${args.joinToString(" ")}")
        val result = providers.exec { commandLine(args) }
        result.result.get().assertNormalExitValue()
        logger.lifecycle("Готово: ${File(dist, "Tilcayo/Tilcayo.exe").absolutePath}")

        // Портативный архив для передачи: dist/Tilcayo-<ver>-win-x64.zip
        // (packageExe пересоздаёт dist, поэтому архив собираем здесь же — иначе он пропадает).
        val zipFile = File(dist, "Tilcayo-1.0.0-win-x64.zip")
        val guide = File(project.projectDir, "packaging/КАК ЗАПУСТИТЬ.txt")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            fun addFile(f: File, name: String) {
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            val gameDir = File(dist, "Tilcayo")
            for (f in gameDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                addFile(f, "Tilcayo/" + f.relativeTo(gameDir).invariantSeparatorsPath)
            }
            if (guide.exists()) addFile(guide, guide.name)
        }
        logger.lifecycle("Архив: ${zipFile.absolutePath} (${zipFile.length() / 1024 / 1024} МБ)")
    }
}
