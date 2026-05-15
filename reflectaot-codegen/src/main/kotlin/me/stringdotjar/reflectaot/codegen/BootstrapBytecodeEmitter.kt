package me.stringdotjar.reflectaot.codegen

import java.io.File
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method

/**
 * Emits `ReflectAOTBootstrap`, which loads before user code via the static initializer on `Reflect` and registers
 * [MethodIdTableBytecodeEmitter.TABLE_INTERNAL] plus the registry into [me.stringdotjar.reflectaot.ReflectAOTServices].
 */
object BootstrapBytecodeEmitter {

  private const val BOOTSTRAP_INTERNAL = "me/stringdotjar/reflectaot/generated/ReflectAOTBootstrap"
  private val SERVICES_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTServices")
  private val REGISTRY_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/generated/ReflectAOTRegistry")
  private val METHOD_TABLE_TYPE = Type.getObjectType(MethodIdTableBytecodeEmitter.TABLE_INTERNAL)
  private val METHOD_ID_RESOLVER_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTMethodIdResolver")
  private val RUNTIME_TYPE = Type.getObjectType("me/stringdotjar/reflectaot/ReflectAOTRuntime")
  private val INSTALL = Method("install", Type.VOID_TYPE, arrayOf(RUNTIME_TYPE))
  private val INSTALL_METHOD_IDS = Method("installMethodIds", Type.VOID_TYPE, arrayOf(METHOD_ID_RESOLVER_TYPE))

  /**
   * Writes `ReflectAOTBootstrap.class` under the generated package directory rooted at [outputDir].
   *
   * @param outputDir Root directory that will receive the `me/stringdotjar/reflectaot/generated` tree.
   */
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
      clinit.newInstance(METHOD_TABLE_TYPE)
      clinit.dup()
      clinit.invokeConstructor(METHOD_TABLE_TYPE, Method.getMethod("void <init> ()"))
      clinit.invokeStatic(SERVICES_TYPE, INSTALL_METHOD_IDS)
      clinit.visitInsn(Opcodes.RETURN)
      clinit.endMethod()
    }
    cw.visitEnd()
    val out = File(outputDir, "me/stringdotjar/reflectaot/generated/ReflectAOTBootstrap.class")
    out.parentFile.mkdirs()
    out.writeBytes(cw.toByteArray())
  }
}
