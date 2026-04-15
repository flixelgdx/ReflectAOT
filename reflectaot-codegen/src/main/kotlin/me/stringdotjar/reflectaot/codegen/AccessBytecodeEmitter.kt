package me.stringdotjar.reflectaot.codegen

import java.io.File
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method as AsmMethod

/**
 * Emits per-type static accessor classes under {@code me.stringdotjar.reflectaot.generated.access}.
 */
object AccessBytecodeEmitter {

  private val OBJECT_TYPE = Type.getType(Object::class.java)
  private val STRING_TYPE = Type.getType(String::class.java)
  private val LIST_TYPE = Type.getType(java.util.List::class.java)
  private val ARRAY_LIST_TYPE = Type.getType(java.util.ArrayList::class.java)

  fun accessInternalName(typeInternal: String): String =
    "me/stringdotjar/reflectaot/generated/access/" + typeInternal.replace('/', '_') + "ReflectAOT"

  fun emit(
    type: TypeIntrospection.IntrospectedType,
    outputDir: File,
    roots: Collection<File>,
  ) {
    val ownerType = Type.getObjectType(type.internalName)
    val accessInternal = accessInternalName(type.internalName)
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    cw.visit(
      Opcodes.V1_7,
      Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
      accessInternal,
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

    emitField(type, cw, ownerType)
    emitSetField(type, cw, ownerType)
    emitHasField(type, cw)
    emitGetProperty(type, cw, ownerType)
    emitSetProperty(type, cw, ownerType)
    emitFields(type, cw, ownerType)

    cw.visitEnd()
    val out = File(outputDir, "$accessInternal.class")
    out.parentFile.mkdirs()
    out.writeBytes(cw.toByteArray())
  }

  private fun emitField(
    type: TypeIntrospection.IntrospectedType,
    cw: ClassWriter,
    ownerType: Type,
  ) {
    val m =
      AsmMethod(
        "field",
        Type.getType(Object::class.java),
        arrayOf(ownerType, STRING_TYPE),
      )
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, m, null, null, cw)
    ga.visitCode()
    ga.loadArg(1)
    val nonNull = ga.newLabel()
    ga.ifNonNull(nonNull)
    ga.throwException(Type.getType(NullPointerException::class.java), "name")
    ga.mark(nonNull)

    for ((name, desc) in type.fields) {
      stringEqualsThen(
        ga,
        name,
        {
          ga.loadArg(0)
          ga.getField(ownerType, name, Type.getType(desc))
          ga.box(Type.getType(desc))
          ga.returnValue()
        },
      )
    }
    ga.throwException(Type.getType(IllegalArgumentException::class.java), "Unknown field")
    ga.endMethod()
  }

  private fun emitSetField(
    type: TypeIntrospection.IntrospectedType,
    cw: ClassWriter,
    ownerType: Type,
  ) {
    val m =
      AsmMethod(
        "setField",
        Type.VOID_TYPE,
        arrayOf(ownerType, STRING_TYPE, OBJECT_TYPE),
      )
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, m, null, null, cw)
    ga.visitCode()
    ga.loadArg(1)
    val nonNull = ga.newLabel()
    ga.ifNonNull(nonNull)
    ga.throwException(Type.getType(NullPointerException::class.java), "name")
    ga.mark(nonNull)

    for ((name, desc) in type.fields) {
      val ft = Type.getType(desc)
      stringEqualsThen(
        ga,
        name,
        {
          ga.loadArg(0)
          ga.loadArg(2)
          ga.unbox(ft)
          ga.putField(ownerType, name, ft)
          ga.returnValue()
        },
      )
    }
    ga.throwException(Type.getType(IllegalArgumentException::class.java), "Unknown field")
    ga.endMethod()
  }

  private fun emitHasField(
    type: TypeIntrospection.IntrospectedType,
    cw: ClassWriter,
  ) {
    val ownerType = Type.getObjectType(type.internalName)
    val m = AsmMethod("hasField", Type.BOOLEAN_TYPE, arrayOf(ownerType, STRING_TYPE))
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, m, null, null, cw)
    ga.visitCode()
    ga.loadArg(1)
    val nonNull = ga.newLabel()
    ga.ifNonNull(nonNull)
    ga.push(false)
    ga.returnValue()
    ga.mark(nonNull)

    val allNames = LinkedHashSet<String>()
    type.fields.keys.forEach { allNames.add(it) }
    type.properties.forEach { allNames.add(it.name) }

    for (n in allNames) {
      stringEqualsThen(
        ga,
        n,
        {
          ga.push(true)
          ga.returnValue()
        },
      )
    }
    ga.push(false)
    ga.returnValue()
    ga.endMethod()
  }

  private fun emitGetProperty(
    type: TypeIntrospection.IntrospectedType,
    cw: ClassWriter,
    ownerType: Type,
  ) {
    val m =
      AsmMethod(
        "getProperty",
        Type.getType(Object::class.java),
        arrayOf(ownerType, STRING_TYPE),
      )
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, m, null, null, cw)
    ga.visitCode()
    ga.loadArg(1)
    val nonNull = ga.newLabel()
    ga.ifNonNull(nonNull)
    ga.throwException(Type.getType(NullPointerException::class.java), "name")
    ga.mark(nonNull)

    for (p in type.properties) {
      stringEqualsThen(
        ga,
        p.name,
        {
          if (p.getterName != null && p.getterDesc != null) {
            ga.loadArg(0)
            val rt = Type.getReturnType(p.getterDesc)
            ga.invokeVirtual(ownerType, AsmMethod(p.getterName!!, p.getterDesc))
            ga.box(rt)
            ga.returnValue()
          } else if (p.fieldName != null && type.fields.containsKey(p.fieldName)) {
            ga.loadArg(0)
            val fd = type.fields[p.fieldName]!!
            ga.getField(ownerType, p.fieldName!!, Type.getType(fd))
            ga.box(Type.getType(fd))
            ga.returnValue()
          }
          ga.throwException(
            Type.getType(IllegalArgumentException::class.java),
            "No readable property " + p.name,
          )
        },
      )
    }
    for ((name, desc) in type.fields) {
      stringEqualsThen(
        ga,
        name,
        {
          ga.loadArg(0)
          ga.getField(ownerType, name, Type.getType(desc))
          ga.box(Type.getType(desc))
          ga.returnValue()
        },
      )
    }
    ga.throwException(Type.getType(IllegalArgumentException::class.java), "Unknown property")
    ga.endMethod()
  }

  private fun emitSetProperty(
    type: TypeIntrospection.IntrospectedType,
    cw: ClassWriter,
    ownerType: Type,
  ) {
    val m =
      AsmMethod(
        "setProperty",
        Type.VOID_TYPE,
        arrayOf(ownerType, STRING_TYPE, OBJECT_TYPE),
      )
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, m, null, null, cw)
    ga.visitCode()
    ga.loadArg(1)
    val nonNull = ga.newLabel()
    ga.ifNonNull(nonNull)
    ga.throwException(Type.getType(NullPointerException::class.java), "name")
    ga.mark(nonNull)

    for (p in type.properties) {
      stringEqualsThen(
        ga,
        p.name,
        {
          if (p.setterName != null && p.setterDesc != null) {
            val args = Type.getArgumentTypes(p.setterDesc)
            if (args.size == 1) {
              ga.loadArg(0)
              ga.loadArg(2)
              ga.unbox(args[0])
              ga.invokeVirtual(ownerType, AsmMethod(p.setterName!!, p.setterDesc))
              ga.returnValue()
            }
          }
          if (p.fieldName != null && type.fields.containsKey(p.fieldName)) {
            ga.loadArg(0)
            ga.loadArg(2)
            val ft = Type.getType(type.fields[p.fieldName]!!)
            ga.unbox(ft)
            ga.putField(ownerType, p.fieldName!!, ft)
            ga.returnValue()
          }
          ga.throwException(
            Type.getType(IllegalArgumentException::class.java),
            "No writable property " + p.name,
          )
        },
      )
    }
    for ((name, desc) in type.fields) {
      stringEqualsThen(
        ga,
        name,
        {
          ga.loadArg(0)
          ga.loadArg(2)
          val ft = Type.getType(desc)
          ga.unbox(ft)
          ga.putField(ownerType, name, ft)
          ga.returnValue()
        },
      )
    }
    ga.throwException(Type.getType(IllegalArgumentException::class.java), "Unknown property")
    ga.endMethod()
  }

  private fun emitFields(
    type: TypeIntrospection.IntrospectedType,
    cw: ClassWriter,
    ownerType: Type,
  ) {
    val m = AsmMethod("fields", LIST_TYPE, arrayOf(ownerType))
    val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, m, null, null, cw)
    ga.visitCode()
    val names = LinkedHashSet<String>()
    type.fields.keys.forEach { names.add(it) }
    type.properties.forEach { names.add(it.name) }

    ga.newInstance(ARRAY_LIST_TYPE)
    ga.dup()
    ga.invokeConstructor(ARRAY_LIST_TYPE, AsmMethod("<init>", "()V"))
    for (n in names) {
      ga.dup()
      ga.push(n)
      ga.invokeVirtual(ARRAY_LIST_TYPE, AsmMethod("add", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE)))
      ga.pop()
    }
    ga.returnValue()
    ga.endMethod()
  }

  private fun stringEqualsThen(
    ga: GeneratorAdapter,
    literal: String,
    thenBlock: () -> Unit,
  ) {
    ga.loadArg(1)
    ga.push(literal)
    ga.invokeVirtual(STRING_TYPE, AsmMethod("equals", Type.BOOLEAN_TYPE, arrayOf(OBJECT_TYPE)))
    val next = ga.newLabel()
    ga.ifZCmp(GeneratorAdapter.EQ, next)
    thenBlock()
    ga.mark(next)
  }
}
