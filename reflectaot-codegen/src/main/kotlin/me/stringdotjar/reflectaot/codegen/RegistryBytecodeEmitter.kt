package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import java.io.File
import org.objectweb.asm.commons.Method as AsmMethod

/**
 * Emits [REGISTRY_INTERNAL]: runtime facade that `instanceof`-dispatches to per-type accessors for
 * field/property/`fields`, and `callMethod` with static bridges for everything else.
 */
object RegistryBytecodeEmitter {

  const val REGISTRY_INTERNAL = "me/stringdotjar/reflectaot/generated/ReflectAOTRegistry"

  private val OBJECT_TYPE = Type.getType(Object::class.java)
  private val STRING_TYPE = Type.getType(String::class.java)
  private val STRING_ARRAY_TYPE = Type.getType("[Ljava/lang/String;")
  private val LIST_TYPE = Type.getType(java.util.List::class.java)
  private val INT_TYPE = Type.INT_TYPE
  private val RUNTIME_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTRuntime")
  private val DEFAULT_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTDefaultDispatch")

  private val M_HAS_FIELD = AsmMethod(ReflectApiNames.HAS_FIELD, Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE))
  private val M_FIELD = AsmMethod(ReflectApiNames.FIELD, OBJECT_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE))
  private val M_SET_FIELD = AsmMethod(ReflectApiNames.SET_FIELD, Type.VOID_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE, OBJECT_TYPE))
  private val M_PROPERTY = AsmMethod(ReflectApiNames.PROPERTY, OBJECT_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE))
  private val M_SET_PROPERTY = AsmMethod(ReflectApiNames.SET_PROPERTY, Type.VOID_TYPE, arrayOf(OBJECT_TYPE, STRING_TYPE, OBJECT_TYPE))
  private val M_CALL_METHOD = AsmMethod(ReflectApiNames.CALL_METHOD, OBJECT_TYPE, arrayOf(OBJECT_TYPE, INT_TYPE, LIST_TYPE))
  private val M_FIELDS = AsmMethod(ReflectApiNames.FIELDS, STRING_ARRAY_TYPE, arrayOf(OBJECT_TYPE))
  private val M_COPY = AsmMethod(ReflectApiNames.COPY, OBJECT_TYPE, arrayOf(OBJECT_TYPE))
  private val M_COMPARE = AsmMethod("compare", Type.INT_TYPE, arrayOf(OBJECT_TYPE, OBJECT_TYPE))
  private val M_COMPARE_METHODS = AsmMethod("compareMethods", Type.BOOLEAN_TYPE, arrayOf(INT_TYPE, INT_TYPE))
  private val M_IS_FUNCTION = AsmMethod("isFunction", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE))
  private val M_IS_OBJECT = AsmMethod("isObject", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE))
  private val M_IS_ENUM = AsmMethod("isEnumValue", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE))

  /** Writes the registry class; requires [AccessBytecodeEmitter] accessors for each [types] entry first. */
  fun emit(types: List<TypeIntrospection.IntrospectedType>, outputDir: File) {
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

    emitDispatchObjectCopy(cw, sorted)

    emitDispatchObjectStringBool(cw, M_HAS_FIELD, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod(ReflectApiNames.HAS_FIELD, Type.BOOLEAN_TYPE, arrayOf(owner, STRING_TYPE))
    }

    emitDispatchObjectStringObject(cw, M_FIELD, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod(ReflectApiNames.FIELD, OBJECT_TYPE, arrayOf(owner, STRING_TYPE))
    }

    emitDispatchObjectStringObjectVoid(cw, M_SET_FIELD, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod(ReflectApiNames.SET_FIELD, Type.VOID_TYPE, arrayOf(owner, STRING_TYPE, OBJECT_TYPE))
    }

    emitDispatchObjectStringObject(cw, M_PROPERTY, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod(ReflectApiNames.PROPERTY, OBJECT_TYPE, arrayOf(owner, STRING_TYPE))
    }

    emitDispatchObjectStringObjectVoid(cw, M_SET_PROPERTY, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod(ReflectApiNames.SET_PROPERTY, Type.VOID_TYPE, arrayOf(owner, STRING_TYPE, OBJECT_TYPE))
    }

    emitDispatchObjectStringArray(cw, M_FIELDS, sorted) { t ->
      val owner = Type.getObjectType(t.internalName)
      AsmMethod(ReflectApiNames.FIELDS, STRING_ARRAY_TYPE, arrayOf(owner))
    }

    emitDispatchObjectIntObject(cw, sorted)

    cw.visitEnd()
    val out = File(outputDir, "$REGISTRY_INTERNAL.class")
    out.parentFile.mkdirs()
    out.writeBytes(cw.toByteArray())
  }

  private fun emitDefaultBridge(cw: ClassWriter, ifaceMethod: AsmMethod, target: Type, targetMethod: AsmMethod) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, ifaceMethod, null, null, cw)
    ga.visitCode()
    for (i in 0 until ifaceMethod.argumentTypes.size) {
      ga.loadArg(i)
    }
    ga.invokeStatic(target, targetMethod)
    ga.returnValue()
    ga.endMethod()
  }

  /**
   * `copy(Object)` → typed `Foo_ReflectAOT.copy(Foo)`, else [ReflectAOTDefaultDispatch.copy].
   */
  private fun emitDispatchObjectCopy(cw: ClassWriter, sorted: List<TypeIntrospection.IntrospectedType>) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, M_COPY, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.loadArg(0)
      ga.invokeStatic(DEFAULT_TYPE, M_COPY)
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
      ga.invokeStatic(access, AsmMethod(ReflectApiNames.COPY, OBJECT_TYPE, arrayOf(owner)))
      ga.returnValue()
      ga.mark(next)
    }
    ga.pop()
    ga.loadArg(0)
    ga.invokeStatic(DEFAULT_TYPE, M_COPY)
    ga.returnValue()
    ga.endMethod()
  }

  private fun emitDispatchObjectStringBool(cw: ClassWriter, ifaceMethod: AsmMethod, sorted: List<TypeIntrospection.IntrospectedType>, accessMethod: (TypeIntrospection.IntrospectedType) -> AsmMethod) {
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

  private fun emitDispatchObjectStringObject(cw: ClassWriter, ifaceMethod: AsmMethod, sorted: List<TypeIntrospection.IntrospectedType>, accessMethod: (TypeIntrospection.IntrospectedType) -> AsmMethod) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, ifaceMethod, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.throwException(
        Type.getType(UnsupportedOperationException::class.java),
        "Reflect.${ReflectApiNames.FIELD} not specialized (no concrete receiver types discovered)",
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
      "Reflect.${ReflectApiNames.FIELD} not specialized for receiver",
    )
    ga.endMethod()
  }

  private fun emitDispatchObjectStringObjectVoid(cw: ClassWriter, ifaceMethod: AsmMethod, sorted: List<TypeIntrospection.IntrospectedType>, accessMethod: (TypeIntrospection.IntrospectedType) -> AsmMethod) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, ifaceMethod, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.throwException(
        Type.getType(UnsupportedOperationException::class.java),
        "Reflect.${ReflectApiNames.SET_FIELD} not specialized (no concrete receiver types discovered)",
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
      "Reflect.${ReflectApiNames.SET_FIELD} not specialized for receiver",
    )
    ga.endMethod()
  }

  private fun emitDispatchObjectIntObject(cw: ClassWriter, sorted: List<TypeIntrospection.IntrospectedType>) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, M_CALL_METHOD, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.loadArg(0)
      ga.loadArg(1)
      ga.loadArg(2)
      ga.invokeStatic(DEFAULT_TYPE, M_CALL_METHOD)
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
      ga.loadArg(2)
      ga.invokeStatic(
        access,
        AsmMethod(ReflectApiNames.CALL_METHOD, OBJECT_TYPE, arrayOf(owner, INT_TYPE, LIST_TYPE)),
      )
      ga.returnValue()
      ga.mark(next)
    }
    ga.pop()
    ga.loadArg(0)
    ga.loadArg(1)
    ga.loadArg(2)
    ga.invokeStatic(DEFAULT_TYPE, M_CALL_METHOD)
    ga.returnValue()
    ga.endMethod()
  }

  private fun emitDispatchObjectStringArray(cw: ClassWriter, ifaceMethod: AsmMethod, sorted: List<TypeIntrospection.IntrospectedType>, accessMethod: (TypeIntrospection.IntrospectedType) -> AsmMethod) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, ifaceMethod, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.invokeStatic(
        Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTDefaultDispatch"),
        AsmMethod("emptyStringArray", STRING_ARRAY_TYPE, arrayOf()),
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
      Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTDefaultDispatch"),
      AsmMethod("emptyStringArray", STRING_ARRAY_TYPE, arrayOf()),
    )
    ga.returnValue()
    ga.endMethod()
  }
}
