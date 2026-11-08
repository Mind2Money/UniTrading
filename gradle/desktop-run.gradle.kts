import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider

// Gradle/Kotlin may delete and recreate build/classes while an earlier `run` JVM is still alive.
// A unique launch snapshot also protects project jars and Compose/native-login resources from later builds.
val runSnapshotParent = File(System.getProperty("java.io.tmpdir")).canonicalFile
val activeRunSnapshots = mutableListOf<File>()

fun newRunSnapshot(): File = Files.createTempDirectory(runSnapshotParent.toPath(), "unitrading-run-").toFile()

fun copyRuntimeFiles(sources: List<File>, snapshot: File): List<File> = sources.filter { it.exists() }.mapIndexed { index, source ->
    val target = File(snapshot, "classpath/$index-${source.name}")
    check(source.copyRecursively(target)) { "Cannot snapshot runtime entry: $source" }
    target
}

fun removeRunSnapshot(snapshot: File) {
    val target = snapshot.canonicalFile
    check(target.parentFile == runSnapshotParent && target.name.startsWith("unitrading-run-")) { "Refusing to remove a path outside the run snapshot directory" }
    if (!target.deleteRecursively()) logger.warn("Could not remove desktop runtime snapshot: $target")
}

val cleanupDesktopRunSnapshot = tasks.register("cleanupDesktopRunSnapshot") {
    doLast { activeRunSnapshots.forEach(::removeRunSnapshot) }
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    finalizedBy(cleanupDesktopRunSnapshot)
    doFirst {
        val snapshot = newRunSnapshot()
        activeRunSnapshots.add(snapshot)
        val copied = copyRuntimeFiles(classpath.files.toList(), snapshot)
        check(copied.isNotEmpty()) { "Desktop runtime classpath is empty" }
        val resources = layout.buildDirectory.dir(if (System.getProperty("os.name").startsWith("Mac")) "appResources/macos" else "appResources/windows").get().asFile
        val copiedResources = File(snapshot, "appResources")
        if (resources.exists()) check(resources.copyRecursively(copiedResources))
        classpath = files(copied)
        // Compose can add this property as a JVM argument; remove it before pointing at the snapshot.
        setJvmArgs(jvmArgs.orEmpty().filterNot { it.startsWith("-Dcompose.application.resources.dir=") })
        systemProperty("compose.application.resources.dir", copiedResources.absolutePath)
        logger.lifecycle("Desktop runtime snapshot: $snapshot")
    }
}

tasks.register("verifyDesktopRunIsolation") {
    group = "verification"
    description = "Verify lazy class/resource loading survives replacement of the original build output."
    doLast {
        val original = newRunSnapshot()
        val snapshot = newRunSnapshot()
        try {
            val classes = File(original, "classes").apply { mkdirs() }
            val resources = File(original, "resources").apply { mkdirs() }
            val source = File(original, "DeferredClass.java").apply {
                writeText("public class DeferredClass { public static String value() { return \"original\"; } }")
            }
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Run verification with a JDK" }
            check(compiler.run(null, null, null, "-d", classes.path, source.path) == 0)
            File(resources, "version.txt").writeText("original resource")
            val copied = copyRuntimeFiles(listOf(classes, resources), snapshot)
            URLClassLoader(copied.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                // The class has deliberately not been loaded yet, matching a UI callback first used after recompilation.
                check(File(classes, "DeferredClass.class").delete())
                File(resources, "version.txt").writeText("replacement resource")
                check(loader.loadClass("DeferredClass").getMethod("value").invoke(null) == "original")
                check(loader.getResourceAsStream("version.txt")!!.bufferedReader().use { it.readText() } == "original resource")
                check(copied.all { it.canonicalPath.startsWith(snapshot.canonicalPath + File.separator) })
            }
            logger.lifecycle("Desktop run isolation verified: deferred classes and resources survive replacement of build output.")
        } finally {
            removeRunSnapshot(original)
            removeRunSnapshot(snapshot)
        }
    }
}
