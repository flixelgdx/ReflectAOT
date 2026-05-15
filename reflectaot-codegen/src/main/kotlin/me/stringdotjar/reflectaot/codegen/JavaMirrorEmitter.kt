package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.Type
import java.io.File

/**
 * Emits Java 7 compatible sources that mirror [AccessBytecodeEmitter] and [RegistryBytecodeEmitter] for toolchains that
 * require `.java` inputs. Method signatures align with the static entry points documented on [ReflectApiNames].
 */
object JavaMirrorEmitter {

  /**
   * Writes one `*_ReflectAOT.java` accessor per type plus `ReflectAOTRegistry.java` under [javaOut].
   *
   * @param javaOut Root directory that will receive the generated Java package tree.
   * @param types Introspected types that need accessor sources.
   * @param methodBindings Full method id binding list used when rendering `callMethod` dispatch.
   * @param roots Classpath roots forwarded to copy and call-method helpers that need subtype checks.
   */
  fun emit(javaOut: File, types: List<TypeIntrospection.IntrospectedType>, methodBindings: List<MethodIdBinding>, roots: Collection<File>) {
    val sorted = types.sortedBy { it.internalName }
    for (t in sorted) {
      emitAccessor(javaOut, t, methodBindings, roots)
    }
    emitRegistry(javaOut, sorted)
  }

  private fun fqcn(internalName: String): String = internalName.replace('/', '.')

  private fun accessClassFqcn(internalName: String): String =
    fqcn(AccessBytecodeEmitter.accessInternalName(internalName))

  private fun emitAccessor(javaOut: File, t: TypeIntrospection.IntrospectedType, methodBindings: List<MethodIdBinding>, roots: Collection<File>) {
    val fq = fqcn(t.internalName)
    val accessFq = accessClassFqcn(t.internalName)
    val pkg = accessFq.substring(0, accessFq.lastIndexOf('.'))
    val simple = accessFq.substring(accessFq.lastIndexOf('.') + 1)
    val dir = File(javaOut, pkg.replace('.', '/'))
    dir.mkdirs()

    val sb = StringBuilder()
    sb.append("package ").append(pkg).append(";\n\n")
    sb.append("import java.util.ArrayList;\n")
    sb.append("import java.util.List;\n\n")
    sb.append("public final class ").append(simple).append(" {\n")
    sb.append("  private ").append(simple).append("() {}\n\n")

    sb.append(renderField(fq, t))
    sb.append(renderSetField(fq, t))
    sb.append(renderHasField(fq, t))
    sb.append(renderProperty(fq, t))
    sb.append(renderSetProperty(fq, t))
    sb.append(renderFields(fq, t))
    sb.append(renderCopy(fq, t, roots))
    sb.append(renderCallMethod(fq, t, methodBindings, roots))

    sb.append("}\n")

    File(dir, "$simple.java").writeText(sb.toString())
  }

  private fun renderField(fq: String, t: TypeIntrospection.IntrospectedType): String {
    val sb = StringBuilder()
    sb.append("  public static Object ").append(ReflectApiNames.FIELD).append("(").append(fq).append(" o, String name) {\n")
    sb.append("    if (name == null) throw new NullPointerException(\"name\");\n")
    for ((name, desc) in t.fields) {
      sb.append("    if (\"").append(escape(name)).append("\".equals(name)) {\n")
      sb.append("      return ").append(boxReadDispatch("o." + name, desc)).append(";\n")
      sb.append("    }\n")
    }
    sb.append("    throw new IllegalArgumentException(\"Unknown field\");\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun renderSetField(fq: String, t: TypeIntrospection.IntrospectedType): String {
    val sb = StringBuilder()
    sb.append("  public static void ").append(ReflectApiNames.SET_FIELD).append("(").append(fq).append(" o, String name, Object value) {\n")
    sb.append("    if (name == null) throw new NullPointerException(\"name\");\n")
    for ((name, desc) in t.fieldsWritable) {
      sb.append("    if (\"").append(escape(name)).append("\".equals(name)) {\n")
      sb
        .append("      o.")
        .append(name)
        .append(" = ")
        .append(unboxAssign("value", desc))
        .append(";\n")
      sb.append("      return;\n")
      sb.append("    }\n")
    }
    sb.append("    throw new IllegalArgumentException(\"Unknown field\");\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun renderHasField(fq: String, t: TypeIntrospection.IntrospectedType): String {
    val names = LinkedHashSet<String>()
    t.instanceFieldsMeta.keys.forEach { names.add(it) }
    t.properties.forEach { names.add(it.name) }
    val sb = StringBuilder()
    sb.append("  public static boolean ").append(ReflectApiNames.HAS_FIELD).append("(").append(fq).append(" o, String name) {\n")
    sb.append("    if (name == null) return false;\n")
    for (n in names) {
      sb.append("    if (\"").append(escape(n)).append("\".equals(name)) return true;\n")
    }
    sb.append("    return false;\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun renderProperty(fq: String, t: TypeIntrospection.IntrospectedType): String {
    val sb = StringBuilder()
    sb.append("  public static Object ").append(ReflectApiNames.PROPERTY).append("(").append(fq).append(" o, String name) {\n")
    sb.append("    if (name == null) throw new NullPointerException(\"name\");\n")
    for (p in t.properties) {
      if (!ReflectPropertyAnalysis.beanReadable(p, t)) {
        continue
      }
      sb.append("    if (\"").append(escape(p.name)).append("\".equals(name)) {\n")
      if (p.getterName != null && p.getterDesc != null) {
        val ret = Type.getReturnType(p.getterDesc).descriptor
        sb.append("      return ").append(boxReadDispatch("o." + p.getterName + "()", ret)).append(";\n")
      } else if (p.fieldName != null && t.fields.containsKey(p.fieldName)) {
        val fd = t.fields[p.fieldName]!!
        sb.append("      return ").append(boxReadDispatch("o." + p.fieldName, fd)).append(";\n")
      }
      sb.append("    }\n")
    }
    for ((name, desc) in t.fields) {
      sb.append("    if (\"").append(escape(name)).append("\".equals(name)) {\n")
      sb.append("      return ").append(boxReadDispatch("o." + name, desc)).append(";\n")
      sb.append("    }\n")
    }
    sb.append("    throw new IllegalArgumentException(\"Unknown property\");\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun renderSetProperty(fq: String, t: TypeIntrospection.IntrospectedType): String {
    val sb = StringBuilder()
    sb.append("  public static void ").append(ReflectApiNames.SET_PROPERTY).append("(").append(fq).append(" o, String name, Object value) {\n")
    sb.append("    if (name == null) throw new NullPointerException(\"name\");\n")
    for (p in t.properties) {
      if (!ReflectPropertyAnalysis.beanWritableForEmit(p, t)) {
        continue
      }
      sb.append("    if (\"").append(escape(p.name)).append("\".equals(name)) {\n")
      if (p.setterName != null && p.setterDesc != null) {
        val args = Type.getArgumentTypes(p.setterDesc)
        if (args.size == 1) {
          val ad = args[0].descriptor
          sb
            .append(
              "      o.",
            ).append(p.setterName)
            .append("(")
            .append(unboxAssign("value", ad))
            .append(");\n")
          sb.append("      return;\n")
        }
      }
      if (p.fieldName != null && t.fieldsWritable.containsKey(p.fieldName)) {
        val fd = t.fieldsWritable[p.fieldName]!!
        sb
          .append("      o.")
          .append(p.fieldName)
          .append(" = ")
          .append(unboxAssign("value", fd))
          .append(";\n")
        sb.append("      return;\n")
      }
      sb.append("    }\n")
    }
    for ((name, desc) in t.fieldsWritable) {
      sb.append("    if (\"").append(escape(name)).append("\".equals(name)) {\n")
      sb
        .append("      o.")
        .append(name)
        .append(" = ")
        .append(unboxAssign("value", desc))
        .append(";\n")
      sb.append("      return;\n")
      sb.append("    }\n")
    }
    sb.append("    throw new IllegalArgumentException(\"Unknown property\");\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun renderFields(fq: String, t: TypeIntrospection.IntrospectedType): String {
    val names = LinkedHashSet<String>()
    t.instanceFieldsMeta.keys.forEach { names.add(it) }
    t.properties.forEach { names.add(it.name) }
    val sb = StringBuilder()
    sb.append("  public static String[] ").append(ReflectApiNames.FIELDS).append("(").append(fq).append(" o) {\n")
    if (names.isEmpty()) {
      sb.append("    return me.stringdotjar.reflectaot.ReflectAOTDefaultDispatch.emptyStringArray();\n")
    } else {
      sb.append("    return new String[] {\n")
      val it = names.iterator()
      while (it.hasNext()) {
        sb.append("      \"").append(escape(it.next())).append("\"")
        if (it.hasNext()) {
          sb.append(",")
        }
        sb.append("\n")
      }
      sb.append("    };\n")
    }
    sb.append("  }\n")
    return sb.toString()
  }

  /** Public no-arg ctor + shallow assignment of each [TypeIntrospection.instanceFieldsForCopy] field (matches bytecode emitter). */
  private fun renderCopy(fq: String, t: TypeIntrospection.IntrospectedType, roots: Collection<File>): String {
    val refs = TypeIntrospection.instanceFieldsForCopy(t.internalName, roots) ?: emptyList()
    val hasCtor = TypeIntrospection.hasPublicNoArgConstructor(t.internalName, roots)
    val sb = StringBuilder()
    sb.append("  public static Object ").append(ReflectApiNames.COPY).append("(").append(fq).append(" src) {\n")
    if (!hasCtor) {
      sb.append("    throw new UnsupportedOperationException(\"No public no-arg constructor for ").append(fq).append("\");\n")
      sb.append("  }\n\n")
      return sb.toString()
    }
    sb.append("    ").append(fq).append(" dest = new ").append(fq).append("();\n")
    for (ref in refs) {
      sb.append("    dest.").append(ref.name).append(" = src.").append(ref.name).append(";\n")
    }
    sb.append("    return dest;\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun renderCallMethod(fq: String, t: TypeIntrospection.IntrospectedType, allBindings: List<MethodIdBinding>, roots: Collection<File>): String {
    val bindings =
      allBindings
        .filter { JvmSubtype.isSubtype(t.internalName, it.userClassInternal, roots) }
        .sortedBy { it.id }
    val sb = StringBuilder()
    sb.append("  public static Object ").append(ReflectApiNames.CALL_METHOD).append("(").append(fq).append(" o, int methodId, java.util.List<?> args) {\n")
    if (bindings.isEmpty()) {
      sb.append(
        "    throw new IllegalArgumentException(\"No Reflect.${ReflectApiNames.METHOD} bindings for $fq\");\n",
      )
      sb.append("  }\n\n")
      return sb.toString()
    }
    sb.append("    switch (methodId) {\n")
    for (b in bindings) {
      sb.append("      case ").append(b.id).append(": {\n")
      val argTypes = Type.getArgumentTypes(b.descriptor)
      for (i in argTypes.indices) {
        sb.append("        ").append(argLocalType(argTypes[i])).append(" a").append(i).append(" = ")
        sb.append(unboxArgFromList("args.get(" + i + ")", argTypes[i])).append(";\n")
      }
      val ret = Type.getReturnType(b.descriptor)
      val argsCsv = (argTypes.indices).joinToString(", ") { "a$it" }
      if (ret == Type.VOID_TYPE) {
        sb.append("        o.").append(b.name).append("(").append(argsCsv).append(");\n")
        sb.append("        return null;\n")
      } else {
        sb.append("        return ").append(boxReadDispatch("o." + b.name + "(" + argsCsv + ")", ret.descriptor)).append(";\n")
      }
      sb.append("      }\n")
    }
    sb.append("      default:\n")
    sb.append("        throw new IllegalArgumentException(\"Unknown method id for " + fq + "\");\n")
    sb.append("    }\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun argLocalType(t: Type): String =
    when (t.sort) {
      Type.BOOLEAN -> "boolean"
      Type.CHAR -> "char"
      Type.BYTE -> "byte"
      Type.SHORT -> "short"
      Type.INT -> "int"
      Type.FLOAT -> "float"
      Type.LONG -> "long"
      Type.DOUBLE -> "double"
      Type.OBJECT, Type.ARRAY -> javaTypeFromDescriptor(t.descriptor)
      else -> "java.lang.Object"
    }

  private fun unboxArgFromList(expr: String, t: Type): String =
    when (t.sort) {
      Type.BOOLEAN -> "((Boolean) " + expr + ").booleanValue()"
      Type.CHAR -> "(char) ((Character) " + expr + ").charValue()"
      Type.BYTE -> "((Number) " + expr + ").byteValue()"
      Type.SHORT -> "((Number) " + expr + ").shortValue()"
      Type.INT -> "((Number) " + expr + ").intValue()"
      Type.FLOAT -> "((Number) " + expr + ").floatValue()"
      Type.LONG -> "((Number) " + expr + ").longValue()"
      Type.DOUBLE -> "((Number) " + expr + ").doubleValue()"
      Type.OBJECT, Type.ARRAY -> "(" + javaTypeFromDescriptor(t.descriptor) + ") " + expr
      else -> "(" + javaTypeFromDescriptor(t.descriptor) + ") " + expr
    }

  /** Registry `copy` dispatches to typed accessor `copy`, then the default stub. */
  private fun renderRegistryCopy(sorted: List<TypeIntrospection.IntrospectedType>): String {
    val sb = StringBuilder()
    sb.append("  public Object ").append(ReflectApiNames.COPY).append("(Object o) {\n")
    if (sorted.isEmpty()) {
      sb.append("    return ReflectAOTDefaultDispatch.").append(ReflectApiNames.COPY).append("(o);\n")
      sb.append("  }\n\n")
      return sb.toString()
    }
    for (t in sorted) {
      val fq = fqcn(t.internalName)
      val acc = accessClassFqcn(t.internalName)
      sb.append("    if (o instanceof ").append(fq).append(") {\n")
      sb.append("      return ").append(acc).append(".").append(ReflectApiNames.COPY).append("((").append(fq).append(") o);\n")
      sb.append("    }\n")
    }
    sb.append("    return ReflectAOTDefaultDispatch.").append(ReflectApiNames.COPY).append("(o);\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  /** Registry `callMethod` chains `instanceof` checks to typed accessor static methods. */
  private fun renderRegistryCallMethod(sorted: List<TypeIntrospection.IntrospectedType>): String {
    val sb = StringBuilder()
    sb.append("  public Object ").append(ReflectApiNames.CALL_METHOD).append("(Object o, int methodId, java.util.List<?> args) {\n")
    if (sorted.isEmpty()) {
      sb.append("    return ReflectAOTDefaultDispatch.").append(ReflectApiNames.CALL_METHOD).append("(o, methodId, args);\n")
      sb.append("  }\n\n")
      return sb.toString()
    }
    for (t in sorted) {
      val fq = fqcn(t.internalName)
      val acc = accessClassFqcn(t.internalName)
      sb.append("    if (o instanceof ").append(fq).append(") {\n")
      sb.append("      return ").append(acc).append(".").append(ReflectApiNames.CALL_METHOD).append("((").append(fq).append(") o, methodId, args);\n")
      sb.append("    }\n")
    }
    sb.append("    return ReflectAOTDefaultDispatch.").append(ReflectApiNames.CALL_METHOD).append("(o, methodId, args);\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun emitRegistry(javaOut: File, sorted: List<TypeIntrospection.IntrospectedType>) {
    val pkg = "me.stringdotjar.reflectaot.generated"
    val dir = File(javaOut, pkg.replace('.', '/'))
    dir.mkdirs()
    val sb = StringBuilder()
    sb.append("package ").append(pkg).append(";\n\n")
    sb.append("import java.util.List;\n")
    sb.append("import me.stringdotjar.reflectaot.ReflectAOTDefaultDispatch;\n")
    sb.append("import me.stringdotjar.reflectaot.ReflectAOTRuntime;\n")
    for (t in sorted) {
      sb.append("import ").append(accessClassFqcn(t.internalName)).append(";\n")
    }
    sb.append("\npublic final class ReflectAOTRegistry implements ReflectAOTRuntime {\n")
    sb.append("  public ReflectAOTRegistry() {}\n\n")

    sb.append("  public int compare(Object a, Object b) { return ReflectAOTDefaultDispatch.compare(a, b); }\n")
    sb.append(
      "  public boolean compareMethods(int methodIdA, int methodIdB) { return ReflectAOTDefaultDispatch.compareMethods(methodIdA, methodIdB); }\n",
    )
    sb.append("  public boolean isFunction(Object v) { return ReflectAOTDefaultDispatch.isFunction(v); }\n")
    sb.append("  public boolean isObject(Object v) { return ReflectAOTDefaultDispatch.isObject(v); }\n")
    sb.append("  public boolean isEnumValue(Object v) { return ReflectAOTDefaultDispatch.isEnumValue(v); }\n")
    sb.append(renderRegistryCallMethod(sorted))
    sb.append(renderRegistryCopy(sorted))

    sb.append(renderRegistryDispatch(ReflectApiNames.HAS_FIELD, "boolean", sorted, ReflectApiNames.HAS_FIELD, booleanReceiverMismatchThrows = true))
    sb.append(renderRegistryDispatch(ReflectApiNames.FIELD, "Object", sorted, ReflectApiNames.FIELD))
    sb.append(renderRegistryDispatchVoid(ReflectApiNames.SET_FIELD, sorted, ReflectApiNames.SET_FIELD))
    sb.append(renderRegistryDispatch(ReflectApiNames.PROPERTY, "Object", sorted, ReflectApiNames.PROPERTY))
    sb.append(renderRegistryDispatchVoid(ReflectApiNames.SET_PROPERTY, sorted, ReflectApiNames.SET_PROPERTY))
    sb.append(renderRegistryFields(sorted))

    sb.append("}\n")
    File(dir, "ReflectAOTRegistry.java").writeText(sb.toString())
  }

  private fun renderRegistryDispatch(
    name: String,
    ret: String,
    sorted: List<TypeIntrospection.IntrospectedType>,
    accessMethod: String,
    booleanReceiverMismatchThrows: Boolean = false,
  ): String {
    val sb = StringBuilder()
    sb
      .append("  public ")
      .append(ret)
      .append(" ")
      .append(name)
      .append("(Object o, String name) {\n")
    if (sorted.isEmpty()) {
      if (ret == "boolean") {
        if (booleanReceiverMismatchThrows) {
          sb
            .append(
              "    throw new UnsupportedOperationException(\"Reflect.",
            ).append(name)
            .append(" not specialized\");\n")
        } else {
          sb.append("    return false;\n")
        }
      } else {
        sb
          .append(
            "    throw new UnsupportedOperationException(\"Reflect.",
          ).append(name)
          .append(" not specialized\");\n")
      }
      sb.append("  }\n\n")
      return sb.toString()
    }
    for (t in sorted) {
      val fq = fqcn(t.internalName)
      val acc = accessClassFqcn(t.internalName)
      sb.append("    if (o instanceof ").append(fq).append(") {\n")
      sb
        .append(
          "      return ",
        ).append(acc)
        .append(".")
        .append(accessMethod)
        .append("((")
        .append(fq)
        .append(") o, name);\n")
      sb.append("    }\n")
    }
    if (ret == "boolean") {
      if (booleanReceiverMismatchThrows) {
        sb
          .append(
            "    throw new UnsupportedOperationException(\"Reflect.",
          ).append(name)
          .append(" not specialized for receiver\");\n")
      } else {
        sb.append("    return false;\n")
      }
    } else {
      sb
        .append(
          "    throw new UnsupportedOperationException(\"Reflect.",
        ).append(name)
        .append(" not specialized for receiver\");\n")
    }
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun renderRegistryDispatchVoid(name: String, sorted: List<TypeIntrospection.IntrospectedType>, accessMethod: String): String {
    val sb = StringBuilder()
    sb.append("  public void ").append(name).append("(Object o, String name, Object value) {\n")
    if (sorted.isEmpty()) {
      sb
        .append(
          "    throw new UnsupportedOperationException(\"Reflect.",
        ).append(name)
        .append(" not specialized\");\n")
      sb.append("  }\n\n")
      return sb.toString()
    }
    for (t in sorted) {
      val fq = fqcn(t.internalName)
      val acc = accessClassFqcn(t.internalName)
      sb.append("    if (o instanceof ").append(fq).append(") {\n")
      sb
        .append(
          "      ",
        ).append(acc)
        .append(".")
        .append(accessMethod)
        .append("((")
        .append(fq)
        .append(") o, name, value);\n")
      sb.append("      return;\n")
      sb.append("    }\n")
    }
    sb
      .append(
        "    throw new UnsupportedOperationException(\"Reflect.",
      ).append(name)
      .append(" not specialized for receiver\");\n")
    sb.append("  }\n\n")
    return sb.toString()
  }

  private fun renderRegistryFields(sorted: List<TypeIntrospection.IntrospectedType>): String {
    val sb = StringBuilder()
    sb.append("  public String[] ").append(ReflectApiNames.FIELDS).append("(Object o) {\n")
    if (sorted.isEmpty()) {
      sb.append("    return me.stringdotjar.reflectaot.ReflectAOTDefaultDispatch.emptyStringArray();\n")
      sb.append("  }\n")
      return sb.toString()
    }
    for (t in sorted) {
      val fq = fqcn(t.internalName)
      val acc = accessClassFqcn(t.internalName)
      sb.append("    if (o instanceof ").append(fq).append(") {\n")
      sb
        .append("      return ")
        .append(acc)
        .append(".")
        .append(ReflectApiNames.FIELDS)
        .append("((")
        .append(fq)
        .append(") o);\n")
      sb.append("    }\n")
    }
    sb.append("    return me.stringdotjar.reflectaot.ReflectAOTDefaultDispatch.emptyStringArray();\n")
    sb.append("  }\n")
    return sb.toString()
  }

  private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

  private fun boxRead(expr: String, desc: String): String =
    when (desc) {
      "I" -> "Integer.valueOf(" + expr + ")"
      "Z" -> "Boolean.valueOf(" + expr + ")"
      "J" -> "Long.valueOf(" + expr + ")"
      "D" -> "Double.valueOf(" + expr + ")"
      "F" -> "Float.valueOf(" + expr + ")"
      "B" -> "Byte.valueOf(" + expr + ")"
      "S" -> "Short.valueOf(" + expr + ")"
      "C" -> "Character.valueOf(" + expr + ")"
      else -> expr
    }

  private fun unboxAssign(value: String, desc: String): String =
    when (desc) {
      "I" -> "((Number) " + value + ").intValue()"
      "Z" -> "((Boolean) " + value + ").booleanValue()"
      "J" -> "((Number) " + value + ").longValue()"
      "D" -> "((Number) " + value + ").doubleValue()"
      "F" -> "((Number) " + value + ").floatValue()"
      "B" -> "((Number) " + value + ").byteValue()"
      "S" -> "((Number) " + value + ").shortValue()"
      "C" -> "(char) ((Character) " + value + ").charValue()"
      else -> "(" + javaTypeFromDescriptor(desc) + ") " + value
    }

  /** Maps a JVM field or method descriptor to a Java source type name, including arrays. */
  private fun javaTypeFromDescriptor(descriptor: String): String {
    var i = 0
    while (i < descriptor.length && descriptor[i] == '[') {
      i++
    }
    val dims = i
    if (i >= descriptor.length) {
      return "java.lang.Object"
    }
    val rest = descriptor.substring(i)
    val base =
      when (rest) {
        "Z" -> "boolean"
        "B" -> "byte"
        "C" -> "char"
        "S" -> "short"
        "I" -> "int"
        "J" -> "long"
        "F" -> "float"
        "D" -> "double"
        "V" -> "void"
        else ->
          if (rest.startsWith("L") && rest.endsWith(";")) {
            rest.substring(1, rest.length - 1).replace('/', '.')
          } else {
            "java.lang.Object"
          }
      }
    return base + "[]".repeat(dims)
  }

  /** Wrap primitives for `Object` APIs; reference and array expressions pass through without extra wrappers. */
  private fun boxReadDispatch(expr: String, desc: String): String =
    when {
      desc.isEmpty() -> expr
      desc[0] == 'L' || desc[0] == '[' -> expr
      else -> boxRead(expr, desc)
    }
}
