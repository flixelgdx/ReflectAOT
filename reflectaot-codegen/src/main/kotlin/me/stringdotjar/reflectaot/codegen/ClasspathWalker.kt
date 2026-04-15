package me.stringdotjar.reflectaot.codegen

import java.io.File
import java.util.zip.ZipFile

/**
 * Walks a classpath entry (directory tree or JAR) and invokes a callback for each {@code .class} resource.
 *
 * <p>Used by ReflectAOT bytecode scanning. ASCII-only KDoc.
 */
object ClasspathWalker {
  fun forEachClassFile(
    root: File,
    consumer: (internalName: String, bytes: ByteArray) -> Unit,
  ) {
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
