package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import java.io.File

/**
 * Emits `me.stringdotjar.reflectaot.generated.ReflectAOTBootstrap` with a static initializer that
 * installs [me.stringdotjar.reflectaot.generated.ReflectAOTRegistry] via [me.stringdotjar.reflectaot.ReflectAOTServices].
 */
object BootstrapBytecodeEmitter {
  private const val BOOTSTRAP_INTERNAL = "me/stringdotjar/reflectaot/generated/ReflectAOTBootstrap"
  private val SERVICES_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTServices")
  private val REGISTRY_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/generated/ReflectAOTRegistry")
  private val RUNTIME_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTRuntime")
  private val INSTALL =
    Method(
      "install",
      Type.VOID_TYPE,
      arrayOf(RUNTIME_TYPE),
    )

  fun emit(outputDir: File) {
    outputDir.mkdirs()
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    cw.visit(
      Opcodes.V1_8,
      Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
      BOOTSTRAP_INTERNAL,
      null,
      "java/lang/Object",
      null,
    )
    run {
      val m = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
      m.visitCode()
      m.visitVarInsn(Opcodes.ALOAD, 0)
      m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      m.visitInsn(Opcodes.RETURN)
      m.visitMaxs(0, 0)
      m.visitEnd()
    }
    run {
      val clinit = GeneratorAdapter(Opcodes.ACC_STATIC, Method("<clinit>", "()V"), null, null, cw)
      clinit.visitCode()
      clinit.newInstance(REGISTRY_TYPE)
      clinit.dup()
      clinit.invokeConstructor(REGISTRY_TYPE, Method.getMethod("void <init> ()"))
      clinit.invokeStatic(SERVICES_TYPE, INSTALL)
      clinit.visitInsn(Opcodes.RETURN)
      clinit.endMethod()
    }
    cw.visitEnd()
    val out = File(outputDir, "me/stringdotjar/reflectaot/generated/ReflectAOTBootstrap.class")
    out.parentFile.mkdirs()
    out.writeBytes(cw.toByteArray())
  }
}
