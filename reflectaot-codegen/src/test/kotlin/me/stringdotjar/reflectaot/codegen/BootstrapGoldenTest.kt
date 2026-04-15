package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.TraceClassVisitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class BootstrapGoldenTest {
  @Test
  fun emitsBootstrapAndRegistryWithoutReflectPackage() {
    val dir = Files.createTempDirectory("reflectaot").toFile()
    ReflectAOTCodegen.run(
      ReflectAOTOutput.CLASS,
      dir,
      File(dir, "javaUnused").apply { mkdirs() },
      emptyList(),
      emptyList(),
      emptyList(),
    )

    val bootstrap = File(dir, "me/stringdotjar/reflectaot/generated/ReflectAOTBootstrap.class")
    assertTrue(bootstrap.exists(), "bootstrap class exists")
    val registry = File(dir, "me/stringdotjar/reflectaot/generated/ReflectAOTRegistry.class")
    assertTrue(registry.exists(), "registry class exists")

    val sw = StringWriter()
    ClassReader(bootstrap.readBytes()).accept(TraceClassVisitor(PrintWriter(sw)), 0)
    val text = sw.toString()
    assertTrue(text.contains("ReflectAOTServices"))
    assertTrue(text.contains("ReflectAOTRegistry"))
    assertTrue(!text.contains("java/lang/reflect/"), "generated bootstrap must not reference java.lang.reflect")
  }
}
