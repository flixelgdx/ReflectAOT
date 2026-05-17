package me.stringdotjar.reflectaot.codegen

import me.stringdotjar.reflectaot.Reflect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

class ReflectUsageScannerLocalConstantTest {

  @TempDir lateinit var tempDir: File

  private fun reflectRuntimeClasspath(): String {
    val loc =
      Reflect::class.java.protectionDomain.codeSource?.location
        ?: error("Reflect has no codeSource (add reflectaot-runtime to test classpath)")
    return File(loc.toURI()).absolutePath
  }

  private class StringSource(private val fqcn: String, private val code: String) :
    SimpleJavaFileObject(
      URI.create("string:///" + fqcn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
      JavaFileObject.Kind.SOURCE,
    ) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
  }

  private fun compileSources(outDir: File, vararg sources: JavaFileObject): Boolean {
    outDir.mkdirs()
    val compiler = ToolProvider.getSystemJavaCompiler() ?: error("no system Java compiler")
    val diagnostics = DiagnosticCollector<JavaFileObject>()
    val fileManager = compiler.getStandardFileManager(null, null, null)
    val opts =
      listOf(
        "-classpath",
        reflectRuntimeClasspath(),
        "-d",
        outDir.absolutePath,
        "-g:vars,lines,source",
      )
    val task = compiler.getTask(null, fileManager, diagnostics, opts, null, sources.toList())
    val ok = task.call()
    if (!ok) {
      diagnostics.diagnostics.forEach { System.err.println(it) }
    }
    return ok
  }

  private fun readClassBytes(outDir: File, internalName: String): ByteArray {
    val f = File(outDir, "$internalName.class")
    check(f.isFile) { "missing $f" }
    return f.readBytes()
  }

  @Test
  fun field_memberName_from_string_local_and_receiver_from_local_table() {
    val out = File(tempDir, "out1")
    val ok =
      compileSources(
        out,
        StringSource(
          "fixture.FieldUse",
          """
          package fixture;
          import me.stringdotjar.reflectaot.Reflect;
          public class FieldUse {
            public static Object read(Holder h) {
              String fieldName = "someField";
              return Reflect.field(h, fieldName);
            }
          }
          """.trimIndent(),
        ),
        StringSource(
          "fixture.Holder",
          """
          package fixture;
          public class Holder {
            public int someField;
          }
          """.trimIndent(),
        ),
      )
    assertTrue(ok)

    val bytes = readClassBytes(out, "fixture/FieldUse")
    val (reflectCalls, _) = ReflectUsageScanner.scanClass(bytes)
    val fieldSite = reflectCalls.single { it.reflectMethod == ReflectApiNames.FIELD }
    assertEquals("someField", fieldSite.nameLiteralOrNull)
    assertEquals("fixture/Holder", fieldSite.receiverInternalOrNull)
  }

  @Test
  fun hasField_resolves_name_through_aliased_string_locals() {
    val out = File(tempDir, "out2")
    val ok =
      compileSources(
        out,
        StringSource(
          "fixture.HasAlias",
          """
          package fixture;
          import me.stringdotjar.reflectaot.Reflect;
          public class HasAlias {
            public static boolean check(Holder a) {
              String aName = "someField";
              String bName = aName;
              return Reflect.hasField(a, bName);
            }
          }
          """.trimIndent(),
        ),
        StringSource(
          "fixture.Holder",
          """
          package fixture;
          public class Holder {
            public int someField;
          }
          """.trimIndent(),
        ),
      )
    assertTrue(ok)

    val bytes = readClassBytes(out, "fixture/HasAlias")
    val (reflectCalls, _) = ReflectUsageScanner.scanClass(bytes)
    val site = reflectCalls.single { it.reflectMethod == ReflectApiNames.HAS_FIELD }
    assertEquals("someField", site.nameLiteralOrNull)
    assertNotNull(site.receiverInternalOrNull)
    assertEquals("fixture/Holder", site.receiverInternalOrNull)
  }

  @Test
  fun method_twoArg_resolves_local_string_name() {
    val out = File(tempDir, "outMethod1")
    val ok =
      compileSources(
        out,
        StringSource(
          "fixture.MethodNameLocal",
          """
          package fixture;
          import me.stringdotjar.reflectaot.Reflect;
          import me.stringdotjar.reflectaot.ReflectMethodId;
          public class MethodNameLocal {
            public static ReflectMethodId id() {
              String n = "run";
              return Reflect.method(Target.class, n);
            }
          }
          """.trimIndent(),
        ),
        StringSource(
          "fixture.Target",
          """
          package fixture;
          public class Target {
            public void run() {}
          }
          """.trimIndent(),
        ),
      )
    assertTrue(ok)
    val bytes = readClassBytes(out, "fixture/MethodNameLocal")
    val (_, mids) = ReflectUsageScanner.scanClass(bytes)
    val site = mids.single()
    assertEquals("fixture/Target", site.ownerClassInternalOrNull)
    assertEquals("run", site.nameOrNull)
    assertEquals(null, site.descriptorOrNull)
  }

  @Test
  fun method_twoArg_resolves_local_class_operand() {
    val out = File(tempDir, "outMethod2")
    val ok =
      compileSources(
        out,
        StringSource(
          "fixture.MethodClassLocal",
          """
          package fixture;
          import me.stringdotjar.reflectaot.Reflect;
          import me.stringdotjar.reflectaot.ReflectMethodId;
          public class MethodClassLocal {
            public static ReflectMethodId id() {
              Class<?> c = Target.class;
              return Reflect.method(c, "run");
            }
          }
          """.trimIndent(),
        ),
        StringSource(
          "fixture.Target",
          """
          package fixture;
          public class Target {
            public void run() {}
          }
          """.trimIndent(),
        ),
      )
    assertTrue(ok)
    val bytes = readClassBytes(out, "fixture/MethodClassLocal")
    val (_, mids) = ReflectUsageScanner.scanClass(bytes)
    val site = mids.single()
    assertEquals("fixture/Target", site.ownerClassInternalOrNull)
    assertEquals("run", site.nameOrNull)
    assertEquals(null, site.descriptorOrNull)
  }

  @Test
  fun method_threeArg_resolves_local_name_and_descriptor() {
    val out = File(tempDir, "outMethod3")
    val ok =
      compileSources(
        out,
        StringSource(
          "fixture.MethodThreeLocal",
          """
          package fixture;
          import me.stringdotjar.reflectaot.Reflect;
          import me.stringdotjar.reflectaot.ReflectMethodId;
          public class MethodThreeLocal {
            public static ReflectMethodId id() {
              Class<?> c = Target.class;
              String n = "run";
              String d = "()V";
              return Reflect.method(c, n, d);
            }
          }
          """.trimIndent(),
        ),
        StringSource(
          "fixture.Target",
          """
          package fixture;
          public class Target {
            public void run() {}
          }
          """.trimIndent(),
        ),
      )
    assertTrue(ok)
    val bytes = readClassBytes(out, "fixture/MethodThreeLocal")
    val (_, mids) = ReflectUsageScanner.scanClass(bytes)
    val site = mids.single()
    assertEquals("fixture/Target", site.ownerClassInternalOrNull)
    assertEquals("run", site.nameOrNull)
    assertEquals("()V", site.descriptorOrNull)
  }

  @Test
  fun setProperty_threeArg_refines_receiver_when_value_is_primitive_false_boxed_to_boolean() {
    val out = File(tempDir, "outSetPropBool")
    val ok =
      compileSources(
        out,
        StringSource(
          "fixture.SetPropBool",
          """
          package fixture;
          import me.stringdotjar.reflectaot.Reflect;
          public class SetPropBool {
            public static void run(Player player) {
              Reflect.setProperty(player, "alive", false);
            }
          }
          """.trimIndent(),
        ),
        StringSource(
          "fixture.Player",
          """
          package fixture;
          public class Player {
            private boolean alive = true;
            public boolean isAlive() { return alive; }
            public void setAlive(boolean v) { this.alive = v; }
          }
          """.trimIndent(),
        ),
      )
    assertTrue(ok)
    val bytes = readClassBytes(out, "fixture/SetPropBool")
    val (reflectCalls, _) = ReflectUsageScanner.scanClass(bytes)
    val site = reflectCalls.single { it.reflectMethod == ReflectApiNames.SET_PROPERTY }
    assertEquals("alive", site.nameLiteralOrNull)
    assertEquals("fixture/Player", site.receiverInternalOrNull)
  }

  @Test
  fun forEachField_resolves_receiver_through_lambda_second_operand() {
    val out = File(tempDir, "outForEachLambda")
    val ok =
      compileSources(
        out,
        StringSource(
          "fixture.ForEachFieldLambda",
          """
          package fixture;
          import me.stringdotjar.reflectaot.Reflect;
          public class ForEachFieldLambda {
            public static void run(Player player) {
              Reflect.forEachField(player, (field, value) -> { });
            }
          }
          """.trimIndent(),
        ),
        StringSource(
          "fixture.Player",
          """
          package fixture;
          public class Player {
            public int score;
          }
          """.trimIndent(),
        ),
      )
    assertTrue(ok)
    val bytes = readClassBytes(out, "fixture/ForEachFieldLambda")
    val (reflectCalls, _) = ReflectUsageScanner.scanClass(bytes)
    val site = reflectCalls.single { it.reflectMethod == ReflectApiNames.FOR_EACH_FIELD }
    assertEquals(null, site.nameLiteralOrNull)
    assertEquals("fixture/Player", site.receiverInternalOrNull)
  }
}
