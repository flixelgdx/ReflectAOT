package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import java.io.File
import org.objectweb.asm.commons.Method as AsmMethod

/**
 * Emits bytecode for `ReflectAOTMethodIdTable`, the class that owns static `ReflectMethodId` constants and `resolve`
 * bridges installed through `ReflectAOTServices.installMethodIds` in the runtime module.
 */
object MethodIdTableBytecodeEmitter {

  /** Internal JVM name of the generated resolver class under the `me.stringdotjar.reflectaot.generated` package. */
  const val TABLE_INTERNAL = "me/stringdotjar/reflectaot/generated/ReflectAOTMethodIdTable"

  private val OBJECT_TYPE = Type.getType(Any::class.java)
  private val STRING_TYPE = Type.getType(String::class.java)
  private val CLASS_TYPE = Type.getType(Class::class.java)
  private val METHOD_ID_TYPE = Type.getType("Lme/stringdotjar/reflectaot/ReflectMethodId;")
  private const val RESOLVER_INTERNAL = "me/stringdotjar/reflectaot/ReflectAOTMethodIdResolver"
  private val TABLE_TYPE = Type.getObjectType(TABLE_INTERNAL)

  private val M_INIT = Method.getMethod("void <init> ()")
  private val M_REFLECT_METHOD_ID_INIT = AsmMethod("<init>", Type.VOID_TYPE, arrayOf(Type.INT_TYPE))
  private val M_RESOLVE = AsmMethod("resolve", METHOD_ID_TYPE, arrayOf(CLASS_TYPE, STRING_TYPE, STRING_TYPE))
  private val M_RESOLVE_CLASS_NAME = AsmMethod("resolve", METHOD_ID_TYPE, arrayOf(CLASS_TYPE, STRING_TYPE))

  /**
   * Writes the resolver class under [outputDir], emitting one static field per binding id.
   *
   * @param outputDir Root directory that will receive the `me/stringdotjar/reflectaot/generated` tree.
   * @param bindings Stable bindings after overload resolution and validation.
   */
  fun emit(outputDir: File, bindings: List<MethodIdBinding>) {
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    cw.visit(
      Opcodes.V1_8,
      Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
      TABLE_INTERNAL,
      null,
      "java/lang/Object",
      arrayOf(RESOLVER_INTERNAL),
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

    val sorted = bindings.sortedBy { it.id }
    for (b in sorted) {
      val f = cw.visitField(
        Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL,
        fieldName(b.id),
        METHOD_ID_TYPE.descriptor,
        null,
        null,
      )
      f.visitEnd()
    }

    run {
      val clinit = GeneratorAdapter(Opcodes.ACC_STATIC, Method("<clinit>", "()V"), null, null, cw)
      clinit.visitCode()
      for (b in sorted) {
        clinit.newInstance(Type.getType("Lme/stringdotjar/reflectaot/ReflectMethodId;"))
        clinit.dup()
        clinit.push(b.id)
        clinit.invokeConstructor(Type.getType("Lme/stringdotjar/reflectaot/ReflectMethodId;"), M_REFLECT_METHOD_ID_INIT)
        clinit.putStatic(TABLE_TYPE, fieldName(b.id), METHOD_ID_TYPE)
      }
      clinit.visitInsn(Opcodes.RETURN)
      clinit.endMethod()
    }

    run {
      val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, M_RESOLVE, null, null, cw)
      ga.visitCode()
      if (sorted.isEmpty()) {
        ga.throwException(
          Type.getType(IllegalArgumentException::class.java),
          "No Reflect.${ReflectApiNames.METHOD} call sites were generated; remove Reflect.${ReflectApiNames.METHOD} calls or run codegen after adding them.",
        )
        ga.endMethod()
      } else {
        for (b in sorted) {
          val next = ga.newLabel()
          ga.loadArg(0)
          ga.visitLdcInsn(Type.getObjectType(b.userClassInternal))
          ga.visitJumpInsn(Opcodes.IF_ACMPNE, next)

          ga.loadArg(1)
          ga.push(b.name)
          ga.invokeVirtual(STRING_TYPE, AsmMethod("equals", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE)))
          ga.ifZCmp(GeneratorAdapter.EQ, next)

          ga.loadArg(2)
          ga.push(b.descriptor)
          ga.invokeVirtual(STRING_TYPE, AsmMethod("equals", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE)))
          ga.ifZCmp(GeneratorAdapter.EQ, next)

          ga.getStatic(TABLE_TYPE, fieldName(b.id), METHOD_ID_TYPE)
          ga.returnValue()
          ga.mark(next)
        }
        ga.throwException(
          Type.getType(IllegalArgumentException::class.java),
          "Unknown Reflect.${ReflectApiNames.METHOD} (class, name, descriptor) combination",
        )
        ga.endMethod()
      }
    }

    emitResolveClassAndNameOnly(cw, sorted)

    cw.visitEnd()
    val out = File(outputDir, "$TABLE_INTERNAL.class")
    out.parentFile.mkdirs()
    out.writeBytes(cw.toByteArray())
  }

  /**
   * Returns the JVM field name used for a static `ReflectMethodId` field in the generated table class.
   *
   * @param id Numeric binding id.
   * @return Field name of the form `M` followed by the decimal id.
   */
  private fun fieldName(id: Int): String = "M$id"

  /**
   * Emits bytecode for `resolve(Class, String)` that counts matches across bindings and fails when ambiguous.
   *
   * @param cw Class writer for the table class under construction.
   * @param sorted Bindings sorted by id for deterministic bytecode layout.
   */
  private fun emitResolveClassAndNameOnly(cw: ClassWriter, sorted: List<MethodIdBinding>) {
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, M_RESOLVE_CLASS_NAME, null, null, cw)
    ga.visitCode()
    if (sorted.isEmpty()) {
      ga.throwException(
        Type.getType(IllegalArgumentException::class.java),
        "No Reflect.${ReflectApiNames.METHOD} call sites were generated; remove Reflect.${ReflectApiNames.METHOD} calls or run codegen after adding them.",
      )
      ga.endMethod()
      return
    }
    val foundSlot = ga.newLocal(METHOD_ID_TYPE)
    val countSlot = ga.newLocal(Type.INT_TYPE)
    ga.visitInsn(Opcodes.ACONST_NULL)
    ga.storeLocal(foundSlot)
    ga.push(0)
    ga.storeLocal(countSlot)
    val ambiguous = ga.newLabel()
    val notFound = ga.newLabel()
    for (b in sorted) {
      val skip = ga.newLabel()
      ga.loadArg(0)
      ga.visitLdcInsn(Type.getObjectType(b.userClassInternal))
      ga.visitJumpInsn(Opcodes.IF_ACMPNE, skip)
      ga.loadArg(1)
      ga.push(b.name)
      ga.invokeVirtual(STRING_TYPE, AsmMethod("equals", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE)))
      ga.ifZCmp(GeneratorAdapter.EQ, skip)
      ga.loadLocal(countSlot)
      ga.visitJumpInsn(Opcodes.IFNE, ambiguous)
      // iinc, not visitIincInsn: newLocal slots are used via raw mv; LVS.visitIincInsn remaps again.
      ga.iinc(countSlot, 1)
      ga.getStatic(TABLE_TYPE, fieldName(b.id), METHOD_ID_TYPE)
      ga.storeLocal(foundSlot)
      ga.mark(skip)
    }
    ga.loadLocal(countSlot)
    ga.visitJumpInsn(Opcodes.IFEQ, notFound)
    ga.loadLocal(foundSlot)
    ga.returnValue()
    ga.mark(notFound)
    ga.throwException(
      Type.getType(IllegalArgumentException::class.java),
      "Unknown Reflect.${ReflectApiNames.METHOD} (class, name); use Reflect.${ReflectApiNames.METHOD}(Class, String, String) with a JVM descriptor.",
    )
    ga.mark(ambiguous)
    ga.throwException(
      Type.getType(IllegalArgumentException::class.java),
      "Ambiguous Reflect.${ReflectApiNames.METHOD} (class, name): multiple overloads share that name; use Reflect.${ReflectApiNames.METHOD}(Class, String, String) with a JVM descriptor.",
    )
    ga.endMethod()
  }
}
