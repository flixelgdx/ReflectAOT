package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import java.io.File
import org.objectweb.asm.commons.Method as AsmMethod

/**
 * Emits {@code me.stringdotjar.reflectaot.generated.ReflectAOTMethodIdTable} implementing {@code
 * ReflectAOTMethodIdResolver}.
 */
object MethodIdTableBytecodeEmitter {

  const val TABLE_INTERNAL = "me/stringdotjar/reflectaot/generated/ReflectAOTMethodIdTable"

  private val OBJECT_TYPE = Type.getType(Object::class.java)
  private val STRING_TYPE = Type.getType(String::class.java)
  private val CLASS_TYPE = Type.getType(Class::class.java)
  private val METHOD_ID_TYPE = Type.getType("Lme/stringdotjar/reflectaot/ReflectMethodId;")
  private val RESOLVER_INTERNAL = "me/stringdotjar/reflectaot/ReflectAOTMethodIdResolver"
  private val TABLE_TYPE = Type.getObjectType(TABLE_INTERNAL)

  private val M_INIT = Method.getMethod("void <init> ()")
  private val M_REFLECT_METHOD_ID_INIT = AsmMethod("<init>", Type.VOID_TYPE, arrayOf(Type.INT_TYPE))
  private val M_RESOLVE =
    AsmMethod(
      "resolve",
      METHOD_ID_TYPE,
      arrayOf(CLASS_TYPE, STRING_TYPE, STRING_TYPE),
    )

  fun emit(
    outputDir: File,
    bindings: List<MethodIdBinding>,
  ) {
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
          "No Reflect.methodId call sites were generated; remove Reflect.methodId calls or run codegen after adding them.",
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
          "Unknown Reflect.methodId (class, name, descriptor) combination",
        )
        ga.endMethod()
      }
    }

    cw.visitEnd()
    val out = File(outputDir, "$TABLE_INTERNAL.class")
    out.parentFile.mkdirs()
    out.writeBytes(cw.toByteArray())
  }

  private fun fieldName(id: Int): String = "M$id"
}
