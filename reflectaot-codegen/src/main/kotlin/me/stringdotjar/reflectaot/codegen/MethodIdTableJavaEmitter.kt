package me.stringdotjar.reflectaot.codegen

import java.io.File

/** Java source mirror of [MethodIdTableBytecodeEmitter] for {@link ReflectAOTOutput#JAVA}. */
object MethodIdTableJavaEmitter {

  fun emit(
    javaOut: File,
    bindings: List<MethodIdBinding>,
  ) {
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
        "    throw new IllegalArgumentException(\"No Reflect.methodId call sites were generated; remove Reflect.methodId calls or run codegen after adding them.\");\n",
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
        "    throw new IllegalArgumentException(\"Unknown Reflect.methodId (class, name, descriptor) combination\");\n",
      )
    }
    sb.append("  }\n}\n")
    File(dir, "ReflectAOTMethodIdTable.java").writeText(sb.toString())
  }

  private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

  /** Renders a {@code .class} literal or {@code int.class} style expression for {@code Reflect.methodId}. */
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
    throw IllegalStateException("Unsupported internal name: $internal")
  }
}
