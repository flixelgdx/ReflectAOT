package me.stringdotjar.reflectaot.codegen

import java.io.File
import org.objectweb.asm.Type

/**
 * Same surface as [MethodIdTableBytecodeEmitter], but emits `.java` for pipelines that consume
 * sources instead of ASM output.
 */
object MethodIdTableJavaEmitter {

  /** Writes `ReflectAOTMethodIdTable.java` implementing [me.stringdotjar.reflectaot.ReflectAOTMethodIdResolver]. */
  fun emit(javaOut: File, bindings: List<MethodIdBinding>) {
    val pkg = "me.stringdotjar.reflectaot.generated"
    val dir = File(javaOut, pkg.replace('.', '/'))
    dir.mkdirs()
    val sb = StringBuilder()
    sb.append("package ").append(pkg).append(";\n\n")
    sb.append("import me.stringdotjar.reflectaot.ReflectAOTMethodIdResolver;\n")
    sb.append("import me.stringdotjar.reflectaot.ReflectMethodId;\n\n")
    sb.append("public final class ReflectAOTMethodIdTable implements ReflectAOTMethodIdResolver {\n\n")
    val sorted = bindings.sortedBy { it.id }
    for (b in sorted) {
      sb.append("  private static final ReflectMethodId M").append(b.id).append(" = new ReflectMethodId(").append(b.id).append(");\n")
    }
    sb.append("\n  public ReflectAOTMethodIdTable() {}\n\n")
    sb.append("  public ReflectMethodId resolve(Class<?> clazz, String name, String descriptor) {\n")
    if (sorted.isEmpty()) {
      sb.append(
        "    throw new IllegalArgumentException(\"No Reflect.${ReflectApiNames.METHOD} call sites were generated; remove Reflect.${ReflectApiNames.METHOD} calls or run codegen after adding them.\");\n",
      )
    } else {
      for (b in sorted) {
        val fq = internalToClassLiteralSource(b.userClassInternal)
        sb.append("    if (clazz == ").append(fq)
        sb.append(" && \"").append(escape(b.name)).append("\".equals(name)")
        sb.append(" && \"").append(escape(b.descriptor)).append("\".equals(descriptor)) {\n")
        sb.append("      return M").append(b.id).append(";\n")
        sb.append("    }\n")
      }
      sb.append(
        "    throw new IllegalArgumentException(\"Unknown Reflect.${ReflectApiNames.METHOD} (class, name, descriptor) combination\");\n",
      )
    }
    sb.append("  }\n\n")
    sb.append("  public ReflectMethodId resolve(Class<?> clazz, String name) {\n")
    if (sorted.isEmpty()) {
      sb.append(
        "    throw new IllegalArgumentException(\"No Reflect.${ReflectApiNames.METHOD} call sites were generated; remove Reflect.${ReflectApiNames.METHOD} calls or run codegen after adding them.\");\n",
      )
    } else {
      sb.append("    int matches = 0;\n")
      sb.append("    ReflectMethodId found = null;\n")
      for (b in sorted) {
        val fq = internalToClassLiteralSource(b.userClassInternal)
        sb.append("    if (clazz == ").append(fq).append(" && \"").append(escape(b.name)).append("\".equals(name)) {\n")
        sb.append("      if (matches != 0) {\n")
        sb.append(
          "        throw new IllegalArgumentException(\"Ambiguous Reflect.${ReflectApiNames.METHOD} (class, name): multiple overloads share that name; use Reflect.${ReflectApiNames.METHOD}(Class, String, String) with a JVM descriptor.\");\n",
        )
        sb.append("      }\n")
        sb.append("      matches = 1;\n")
        sb.append("      found = M").append(b.id).append(";\n")
        sb.append("    }\n")
      }
      sb.append("    if (matches == 0) {\n")
      sb.append(
        "      throw new IllegalArgumentException(\"Unknown Reflect.${ReflectApiNames.METHOD} (class, name); use Reflect.${ReflectApiNames.METHOD}(Class, String, String) with a JVM descriptor.\");\n",
      )
      sb.append("    }\n")
      sb.append("    return found;\n")
    }
    sb.append("  }\n}\n")
    File(dir, "ReflectAOTMethodIdTable.java").writeText(sb.toString())
  }

  private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

  /** Renders a `.class` literal or `int.class`-style primitive class literal for use in emitted Java. */
  private fun internalToClassLiteralSource(internal: String): String {
    if (internal.startsWith("[")) {
      var s = internal
      var dims = 0
      while (s.startsWith("[")) {
        dims++
        s = s.substring(1)
      }
      val base =
        when (s) {
          "I" -> "int"
          "Z" -> "boolean"
          "B" -> "byte"
          "S" -> "short"
          "C" -> "char"
          "J" -> "long"
          "F" -> "float"
          "D" -> "double"
          "V" -> "void"
          else ->
            if (s.startsWith("L") && s.endsWith(";")) {
              s.substring(1, s.length - 1).replace('/', '.')
            } else {
              throw IllegalStateException("Unsupported type in descriptor: $internal")
            }
        }
      return base + "[]".repeat(dims) + ".class"
    }
    if (internal.startsWith("L") && internal.endsWith(";")) {
      return internal.substring(1, internal.length - 1).replace('/', '.') + ".class"
    }
    // [MethodIdBinding.userClassInternal] uses ASM internal names (slashes), not L…; descriptors.
    return Type.getObjectType(internal).className + ".class"
  }
}
