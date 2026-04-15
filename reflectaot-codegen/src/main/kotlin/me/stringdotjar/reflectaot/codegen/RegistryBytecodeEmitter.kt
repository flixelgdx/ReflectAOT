package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import java.io.File
import org.objectweb.asm.commons.Method as AsmMethod

/**
 * Emits {@code me.stringdotjar.reflectaot.generated.ReflectAOTRegistry} implementing {@code ReflectAOTRuntime}.
 */
object RegistryBytecodeEmitter {
  const val REGISTRY_INTERNAL = "me/stringdotjar/reflectaot/generated/ReflectAOTRegistry"

  private val OBJECT_TYPE = Type.getType(Object::class.java)
  private val STRING_TYPE = Type.getType(String::class.java)
  private val LIST_TYPE = Type.getType(java.util.List::class.java)
  private val RUNTIME_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTRuntime")
  private val DEFAULT_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTDefaultDispatch")

  private val M_HAS_FIELD = AsmMethod("hasField", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE))
  private val M_FIELD = AsmMethod("field", OBJECT_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE))
  private val M_SET_FIELD = AsmMethod("setField", Type.VOID_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE, OBJECT_TYPE))
  private val M_GET_PROPERTY = AsmMethod("getProperty", OBJECT_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE))
  private val M_SET_PROPERTY =
    AsmMethod("setProperty", Type.VOID_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE, OBJECT_TYPE))
  private val M_CALL_METHOD = AsmMethod("callMethod", OBJECT_TYPE, arrayOf(OBJECT_TYPE, OBJECT_TYPE, LIST_TYPE))
  private val M_FIELDS = AsmMethod("fields", LIST_TYPE, arrayOf(OBJECT_TYPE))
  private val M_COPY = AsmMethod("copy", OBJECT_TYPE, arrayOf(OBJECT_TYPE))
  private val M_DELETE_FIELD = AsmMethod("deleteField", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE))
  private val M_COMPARE = AsmMethod("compare", Type.INT_TYPE, arrayOf(OBJECT_TYPE, OBJECT_TYPE))
  private val M_COMPARE_METHODS = AsmMethod("compareMethods", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE, OBJECT_TYPE))
  private val M_IS_FUNCTION = AsmMethod("isFunction", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE))
  private val M_IS_OBJECT = AsmMethod("isObject", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE))
  private val M_MAKE_VAR_ARGS = AsmMethod("makeVarArgs", OBJECT_TYPE, arrayOf(OBJECT_TYPE))
  private val M_IS_ENUM = AsmMethod("isEnumValue", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE))

  fun emit(
    types: List<TypeIntrospection.IntrospectedType>,
    outputDir: File,
  ) {
    val sorted = types.sortedBy { it.internalName }
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    cw.visit(
      Opcodes.V1_7,
      Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
      REGISTRY_INTERNAL,
      null,
      "java/lang/Object",
      arrayOf(RUNTIME_TYPE.internalName),
    )
    run {
      val m = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
      m.visitCode()
      m.visitVarInsn(Opcodes.ALOAD, 0)
      m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      m.visitInsn(Opcodes.RETURN)
      m.visitMaxs(0, 0)
      m.visitEnd()
    }

    emitDefaultBridge(cw, M_COMPARE, DEFAULT_TYPE, M_COMPARE)
    emitDefaultBridge(cw, M_COMPARE_METHODS, DEFAULT_TYPE, M_COMPARE_METHODS)
    emitDefaultBridge(cw, M_IS_FUNCTION, DEFAULT_TYPE, M_IS_FUNCTION)
    emitDefaultBridge(cw, M_IS_OBJECT, DEFAULT_TYPE, M_IS_OBJECT)
    emitDefaultBridge(cw, M_IS_ENUM, DEFAULT_TYPE, M_IS_ENUM)
    emitDefaultBridge(cw, M_MAKE_VAR_ARGS, DEFAULT_TYPE, M_MAKE_VAR_ARGS)
    emitDefaultBridge(cw, M_CALL_METHOD, DEFAULT_TYPE, M_CALL_METHOD)
    emitDefaultBridge(cw, M_COPY, DEFAULT_TYPE, M_COPY)
    emitDefaultBridge(cw, M_DELETE_FIELD, DEFAULT_TYPE, M_DELETE_FIELD)

    emitDispatchObjectStringBool(cw, M_HAS_FIELD, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod("hasField", Type.BOOLEAN_TYPE, arrayOf(owner, STRING_TYPE))
    }

    emitDispatchObjectStringObject(cw, M_FIELD, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod("field", OBJECT_TYPE, arrayOf(owner, STRING_TYPE))
    }

    emitDispatchObjectStringObjectVoid(cw, M_SET_FIELD, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod("setField", Type.VOID_TYPE, arrayOf(owner, STRING_TYPE, OBJECT_TYPE))
    }

    emitDispatchObjectStringObject(cw, M_GET_PROPERTY, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod("getProperty", OBJECT_TYPE, arrayOf(owner, STRING_TYPE))
    }

    emitDispatchObjectStringObjectVoid(cw, M_SET_PROPERTY, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod("setProperty", Type.VOID_TYPE, arrayOf(owner, STRING_TYPE, OBJECT_TYPE))
    }

    emitDispatchObjectList(cw, M_FIELDS, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod("fields", LIST_TYPE, arrayOf(owner))
    }

    cw.visitEnd()
    val out = File(outputDir, "$REGISTRY_INTERNAL.class")
    out.parentFile.mkdirs()
    out.writeBytes(cw.toByteArray())
  }

  private fun emitDefaultBridge(
    cw: ClassWriter,
    ifaceMethod: AsmMethod,
    target: Type,
    targetMethod: AsmMethod,
  ) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, ifaceMethod, null, null, cw)
    ga.visitCode()
    for (i in 0 until ifaceMethod.argumentTypes.size) {
      ga.loadArg(i)
    }
    ga.invokeStatic(target, targetMethod)
    ga.returnValue()
    ga.endMethod()
  }

  private fun emitDispatchObjectStringBool(
    cw: ClassWriter,
    ifaceMethod: AsmMethod,
    sorted: List<TypeIntrospection.IntrospectedType>,
    accessMethod: (TypeIntrospection.IntrospectedType) -> AsmMethod,
  ) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, ifaceMethod, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.push(false)
      ga.returnValue()
      ga.endMethod()
      return
    }
    ga.loadArg(0)
    for (t in sorted) {
      val owner = Type.getObjectType(t.internalName)
      val access = Type.getObjectType(AccessBytecodeEmitter.accessInternalName(t.internalName))
      val next = ga.newLabel()
      ga.dup()
      ga.instanceOf(owner)
      ga.ifZCmp(GeneratorAdapter.EQ, next)
      ga.checkCast(owner)
      ga.loadArg(1)
      ga.invokeStatic(access, accessMethod(t))
      ga.returnValue()
      ga.mark(next)
    }
    ga.pop()
    ga.push(false)
    ga.returnValue()
    ga.endMethod()
  }

  private fun emitDispatchObjectStringObject(
    cw: ClassWriter,
    ifaceMethod: AsmMethod,
    sorted: List<TypeIntrospection.IntrospectedType>,
    accessMethod: (TypeIntrospection.IntrospectedType) -> AsmMethod,
  ) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, ifaceMethod, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.throwException(
        Type.getType(UnsupportedOperationException::class.java),
        "Reflect.field not specialized (no concrete receiver types discovered)",
      )
      ga.endMethod()
      return
    }
    ga.loadArg(0)
    for (t in sorted) {
      val owner = Type.getObjectType(t.internalName)
      val access = Type.getObjectType(AccessBytecodeEmitter.accessInternalName(t.internalName))
      val next = ga.newLabel()
      ga.dup()
      ga.instanceOf(owner)
      ga.ifZCmp(GeneratorAdapter.EQ, next)
      ga.checkCast(owner)
      ga.loadArg(1)
      ga.invokeStatic(access, accessMethod(t))
      ga.returnValue()
      ga.mark(next)
    }
    ga.pop()
    ga.throwException(
      Type.getType(UnsupportedOperationException::class.java),
      "Reflect.field not specialized for receiver",
    )
    ga.endMethod()
  }

  private fun emitDispatchObjectStringObjectVoid(
    cw: ClassWriter,
    ifaceMethod: AsmMethod,
    sorted: List<TypeIntrospection.IntrospectedType>,
    accessMethod: (TypeIntrospection.IntrospectedType) -> AsmMethod,
  ) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, ifaceMethod, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.throwException(
        Type.getType(UnsupportedOperationException::class.java),
        "Reflect.setField not specialized (no concrete receiver types discovered)",
      )
      ga.endMethod()
      return
    }
    ga.loadArg(0)
    for (t in sorted) {
      val owner = Type.getObjectType(t.internalName)
      val access = Type.getObjectType(AccessBytecodeEmitter.accessInternalName(t.internalName))
      val next = ga.newLabel()
      ga.dup()
      ga.instanceOf(owner)
      ga.ifZCmp(GeneratorAdapter.EQ, next)
      ga.checkCast(owner)
      ga.loadArg(1)
      ga.loadArg(2)
      ga.invokeStatic(access, accessMethod(t))
      ga.returnValue()
      ga.mark(next)
    }
    ga.pop()
    ga.throwException(
      Type.getType(UnsupportedOperationException::class.java),
      "Reflect.setField not specialized for receiver",
    )
    ga.endMethod()
  }

  private fun emitDispatchObjectList(
    cw: ClassWriter,
    ifaceMethod: AsmMethod,
    sorted: List<TypeIntrospection.IntrospectedType>,
    accessMethod: (TypeIntrospection.IntrospectedType) -> AsmMethod,
  ) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, ifaceMethod, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.invokeStatic(
        Type.getType(java.util.Collections::class.java),
        AsmMethod("emptyList", LIST_TYPE, arrayOf()),
      )
      ga.returnValue()
      ga.endMethod()
      return
    }
    ga.loadArg(0)
    for (t in sorted) {
      val owner = Type.getObjectType(t.internalName)
      val access = Type.getObjectType(AccessBytecodeEmitter.accessInternalName(t.internalName))
      val next = ga.newLabel()
      ga.dup()
      ga.instanceOf(owner)
      ga.ifZCmp(GeneratorAdapter.EQ, next)
      ga.checkCast(owner)
      ga.invokeStatic(access, accessMethod(t))
      ga.returnValue()
      ga.mark(next)
    }
    ga.pop()
    ga.invokeStatic(
      Type.getType(java.util.Collections::class.java),
      AsmMethod("emptyList", LIST_TYPE, arrayOf()),
    )
    ga.returnValue()
    ga.endMethod()
  }
}
