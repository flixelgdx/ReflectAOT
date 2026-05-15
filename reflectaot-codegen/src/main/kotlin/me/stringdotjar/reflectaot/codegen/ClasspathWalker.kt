package me.stringdotjar.reflectaot.codegen

import java.io.File
import java.util.zip.ZipFile

/**
 * Low-level filesystem helper that enumerates `.class` resources under a single classpath entry.
 *
 * This object is used by [ReflectUsageScanner.scanClasspath] so scanning can treat exploded directories and
 * packaged JAR files uniformly. Each discovered class is passed to the caller as raw bytes plus its JVM internal name.
 */
object ClasspathWalker {

  /**
   * Invokes [consumer] once per `.class` file found under [root], skipping `module-info.class`.
   *
   * When [root] is a directory, this walks recursively. When [root] is a file ending in `.jar`, entries are read from
   * the ZIP stream. Missing roots are ignored.
   *
   * @param root A classes output directory or a JAR file from a Java compile or packaged dependency.
   * @param consumer Receives the slash-separated internal class name (without `.class`) and the raw class bytes.
   */
  fun forEachClassFile(root: File, consumer: (internalName: String, bytes: ByteArray) -> Unit) {
    if (!root.exists()) {
      return
    }
    if (root.isDirectory) {
      root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
        val rel =
          root
            .toPath()
            .relativize(f.toPath())
            .toString()
            .replace(File.separatorChar, '/')
        if (rel == "module-info.class" || rel.endsWith("/module-info.class")) {
          return@forEach
        }
        val internal = rel.removeSuffix(".class")
        consumer(internal, f.readBytes())
      }
    } else if (root.isFile && root.name.endsWith(".jar")) {
      ZipFile(root).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
          val e = entries.nextElement()
          if (e.isDirectory) {
            continue
          }
          val name = e.name
          if (!name.endsWith(".class") ||
            name == "module-info.class" ||
            name.endsWith("/module-info.class")
          ) {
            continue
          }
          val internal = name.removeSuffix(".class")
          zip.getInputStream(e).use { ins ->
            consumer(internal, ins.readBytes())
          }
        }
      }
    }
  }
}
