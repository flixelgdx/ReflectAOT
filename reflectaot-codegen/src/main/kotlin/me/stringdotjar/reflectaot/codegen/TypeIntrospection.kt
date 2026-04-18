package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.zip.ZipFile

/**
 * Parses `.class` bytes into field lists, JavaBeans-style properties, and public instance methods.
 *
 * Superclasses are walked so subclass fields shadow correctly and override-ordered methods are collected.
 */
object TypeIntrospection {

  /** Aggregates getter/setter metadata for JavaBeans-backed `Reflect.property` / `Reflect.setProperty`. */
  data class BeanProperty(
    val name: String,
    val getterName: String?,
    val getterDesc: String?,
    val setterName: String?,
    val setterDesc: String?,
    val fieldName: String?,
  )

  /** One override-retained public instance method (first declaring type wins per name+descriptor). */
  data class InstanceMethod(
    val name: String,
    val descriptor: String,
    val declaringInternal: String,
  )

  /** Snapshot used by accessors, registry dispatch, and `Reflect.method` validation. */
  data class IntrospectedType(
    val internalName: String,
    /** Declared instance field names (subclass before superclass) mapped to field descriptors. */
    val fields: LinkedHashMap<String, String>,
    val properties: List<BeanProperty>,
    /** Public instance methods (most specific override first), keyed by name+descriptor. */
    val instanceMethods: List<InstanceMethod>,
  )

  /**
   * Loads [internalName] from [roots], merges superclass metadata, or returns null if bytes are missing.
   */
  fun load(internalName: String, roots: Collection<File>): IntrospectedType? {
    val bytes = loadClassBytes(internalName, roots) ?: return null
    val start = ClassNode()
    ClassReader(bytes).accept(start, ClassReader.SKIP_DEBUG)
    val chain = buildSuperChain(start, roots)
    val fieldsInOrder = LinkedHashMap<String, String>()
    for (layer in chain) {
      for (fn in layer.fields) {
        if ((fn.access and Opcodes.ACC_STATIC) != 0) {
          continue
        }
        if ((fn.access and Opcodes.ACC_SYNTHETIC) != 0) {
          continue
        }
        if (!fieldsInOrder.containsKey(fn.name)) {
          fieldsInOrder[fn.name!!] = fn.desc!!
        }
      }
    }

    val instanceMethodsByKey = LinkedHashMap<Pair<String, String>, InstanceMethod>()
    for (layer in chain) {
      val decl = layer.name ?: continue
      for (mn in layer.methods) {
        val acc = mn.access
        if ((acc and Opcodes.ACC_STATIC) != 0) {
          continue
        }
        if ((acc and Opcodes.ACC_SYNTHETIC) != 0) {
          continue
        }
        if (mn.name == "<init>") {
          continue
        }
        if ((acc and Opcodes.ACC_PUBLIC) == 0) {
          continue
        }
        val n = mn.name ?: continue
        val d = mn.desc ?: continue
        instanceMethodsByKey.putIfAbsent(n to d, InstanceMethod(n, d, decl))
      }
    }

    val props = LinkedHashMap<String, BeanProperty>()
    for (layer in chain) {
      for (mn in layer.methods) {
        val acc = mn.access
        if ((acc and Opcodes.ACC_STATIC) != 0) {
          continue
        }
        if ((acc and Opcodes.ACC_SYNTHETIC) != 0) {
          continue
        }
        val name = mn.name ?: continue
        val desc = mn.desc ?: continue
        if (name.startsWith("get") && name.length > 3 && desc.startsWith("()") && desc != "()V") {
          val prop = decapitalize(name.substring(3))
          val existing = props[prop]
          props[prop] =
            BeanProperty(
              name = prop,
              getterName = name,
              getterDesc = desc,
              setterName = existing?.setterName,
              setterDesc = existing?.setterDesc,
              fieldName = existing?.fieldName ?: inferFieldForProperty(prop, fieldsInOrder.keys),
            )
        } else if (name.startsWith("is") && name.length > 2 && desc == "()Z") {
          val prop = decapitalize(name.substring(2))
          val existing = props[prop]
          props[prop] =
            BeanProperty(
              name = prop,
              getterName = name,
              getterDesc = desc,
              setterName = existing?.setterName,
              setterDesc = existing?.setterDesc,
              fieldName = existing?.fieldName ?: inferFieldForProperty(prop, fieldsInOrder.keys),
            )
        } else if (name.startsWith("set") && name.length > 3) {
          val argTypes = Type.getArgumentTypes(desc)
          if (argTypes.size == 1 && desc.endsWith(")V")) {
            val prop = decapitalize(name.substring(3))
            val existing = props[prop]
            props[prop] =
              BeanProperty(
                name = prop,
                getterName = existing?.getterName,
                getterDesc = existing?.getterDesc,
                setterName = name,
                setterDesc = desc,
                fieldName = existing?.fieldName ?: inferFieldForProperty(prop, fieldsInOrder.keys),
              )
          }
        }
      }
    }

    return IntrospectedType(
      internalName = internalName,
      fields = fieldsInOrder,
      properties = props.values.toList(),
      instanceMethods = instanceMethodsByKey.values.toList(),
    )
  }

  /** Superclass-first chain from [start] toward [java/lang/Object] (object itself not included last). */
  private fun buildSuperChain(start: ClassNode, roots: Collection<File>): List<ClassNode> {
    val out = ArrayList<ClassNode>()
    var cur: ClassNode? = start
    while (cur != null && cur.name != "java/lang/Object") {
      out.add(cur)
      val superName = cur.superName ?: break
      val supBytes = loadClassBytes(superName, roots) ?: break
      val sup = ClassNode()
      ClassReader(supBytes).accept(sup, ClassReader.SKIP_DEBUG)
      cur = sup
    }
    return out
  }

  private fun inferFieldForProperty(prop: String, fieldNames: Set<String>): String? {
    if (fieldNames.contains(prop)) {
      return prop
    }
    return null
  }

  private fun decapitalize(s: String): String {
    if (s.isEmpty()) {
      return s
    }
    val c0 = s[0]
    if (s.length > 1 && c0.isUpperCase() && s[1].isUpperCase()) {
      return s
    }
    return Character.toLowerCase(c0) + s.substring(1)
  }

  /** Locates `internalName.class` under a directory root or inside a JAR entry. */
  fun loadClassBytes(internalName: String, roots: Collection<File>): ByteArray? {
    val relPath = internalName + ".class"
    for (root in roots) {
      if (root.isDirectory) {
        val f = File(root, relPath)
        if (f.isFile) {
          return f.readBytes()
        }
      } else if (root.isFile && root.name.endsWith(".jar")) {
        ZipFile(root).use { zip ->
          val e = zip.getEntry(relPath) ?: return@use
          zip.getInputStream(e).use { ins ->
            return ins.readBytes()
          }
        }
      }
    }
    return null
  }
}
