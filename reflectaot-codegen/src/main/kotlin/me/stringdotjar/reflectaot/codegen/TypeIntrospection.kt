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

  /** JVM field metadata used for visibility checks and discovery (`hasField`, `Reflect.fields`). */
  data class InstanceFieldMeta(
    val descriptor: String,
    val access: Int,
  )

  /** Snapshot used by accessors, registry dispatch, and `Reflect.method` validation. */
  data class IntrospectedType(
    val internalName: String,
    /**
     * Public instance fields only — subclass-before-superclass shadowing preserved.
     * Used for reads (`Reflect.field`) and readable field fallbacks in `Reflect.property`.
     */
    val fields: LinkedHashMap<String, String>,
    /**
     * Public, non-final instance fields — subclass-before-superclass shadowing preserved.
     * Used for writes (`Reflect.setField`) and writable field fallbacks in `Reflect.setProperty`.
     */
    val fieldsWritable: LinkedHashMap<String, String>,
    /**
     * Every non-static, non-synthetic instance field (any visibility), same ordering as [fields] shadowing rules.
     * Used for validation and name discovery; bytecode must not emit illegal access from here alone.
     */
    val instanceFieldsMeta: LinkedHashMap<String, InstanceFieldMeta>,
    val properties: List<BeanProperty>,
    /** Public instance methods (most specific override first), keyed by name+descriptor. */
    val instanceMethods: List<InstanceMethod>,
  )

  /** Human-readable JVM visibility for error messages. */
  fun visibilityWord(access: Int): String =
    when {
      (access and Opcodes.ACC_PUBLIC) != 0 -> "public"
      (access and Opcodes.ACC_PROTECTED) != 0 -> "protected"
      (access and Opcodes.ACC_PRIVATE) != 0 -> "private"
      else -> "package-private"
    }

  /**
   * One **public**, non-static, non-final instance field eligible for bytecode `getfield`/`putfield` from generated accessors.
   *
   * @property name JVM field name.
   * @property descriptor Field type descriptor (`I`, `Ljava/lang/String;`, …).
   * @property declaringInternal Internal name of the class that declares the field (superclass fields included).
   */
  data class InstanceFieldRef(
    val name: String,
    val descriptor: String,
    val declaringInternal: String,
  )

  /**
   * Loads [internalName] from [roots], merges superclass metadata, or returns null if bytes are missing.
   */
  fun load(internalName: String, roots: Collection<File>): IntrospectedType? {
    val bytes = loadClassBytes(internalName, roots) ?: return null
    val start = ClassNode()
    ClassReader(bytes).accept(start, ClassReader.SKIP_DEBUG)
    val chain = buildSuperChain(start, roots)
    val instanceFieldsMeta = LinkedHashMap<String, InstanceFieldMeta>()
    for (layer in chain) {
      for (fn in layer.fields) {
        if ((fn.access and Opcodes.ACC_STATIC) != 0) {
          continue
        }
        if ((fn.access and Opcodes.ACC_SYNTHETIC) != 0) {
          continue
        }
        val nm = fn.name ?: continue
        if (!instanceFieldsMeta.containsKey(nm)) {
          instanceFieldsMeta[nm] = InstanceFieldMeta(fn.desc ?: continue, fn.access)
        }
      }
    }
    val fieldsPublicOnly = LinkedHashMap<String, String>()
    val fieldsWritablePublic = LinkedHashMap<String, String>()
    for ((name, meta) in instanceFieldsMeta) {
      if ((meta.access and Opcodes.ACC_PUBLIC) != 0) {
        fieldsPublicOnly[name] = meta.descriptor
        if ((meta.access and Opcodes.ACC_FINAL) == 0) {
          fieldsWritablePublic[name] = meta.descriptor
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
        if ((acc and Opcodes.ACC_PUBLIC) == 0) {
          continue
        }
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
              fieldName = existing?.fieldName ?: inferFieldForProperty(prop, instanceFieldsMeta.keys),
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
              fieldName = existing?.fieldName ?: inferFieldForProperty(prop, instanceFieldsMeta.keys),
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
                fieldName = existing?.fieldName ?: inferFieldForProperty(prop, instanceFieldsMeta.keys),
              )
          }
        }
      }
    }

    return IntrospectedType(
      internalName = internalName,
      fields = fieldsPublicOnly,
      fieldsWritable = fieldsWritablePublic,
      instanceFieldsMeta = instanceFieldsMeta,
      properties = props.values.toList(),
      instanceMethods = instanceMethodsByKey.values.toList(),
    )
  }

  /**
   * Lists public, non-final instance fields for shallow `Reflect.copy`, subclass-before-superclass (shadowing preserved).
   *
   * Private, package, or protected fields are omitted because generated accessors live outside the declaring class.
   * Final fields are omitted (not assignable from a static helper).
   *
   * @param internalName Leaf type internal name.
   * @param roots Classpath used to walk superclasses.
   * @return Ordered field refs, or null if the leaf class bytes are missing.
   */
  fun instanceFieldsForCopy(internalName: String, roots: Collection<File>): List<InstanceFieldRef>? {
    val bytes = loadClassBytes(internalName, roots) ?: return null
    val start = ClassNode()
    ClassReader(bytes).accept(start, ClassReader.SKIP_DEBUG)
    val chain = buildSuperChain(start, roots)
    val seen = LinkedHashSet<String>()
    val out = ArrayList<InstanceFieldRef>()
    for (layer in chain) {
      val decl = layer.name ?: continue
      for (fn in layer.fields) {
        if ((fn.access and Opcodes.ACC_STATIC) != 0) {
          continue
        }
        if ((fn.access and Opcodes.ACC_SYNTHETIC) != 0) {
          continue
        }
        if ((fn.access and Opcodes.ACC_FINAL) != 0) {
          continue
        }
        if ((fn.access and Opcodes.ACC_PUBLIC) == 0) {
          continue
        }
        val nm = fn.name ?: continue
        if (!seen.add(nm)) {
          continue
        }
        out.add(InstanceFieldRef(nm, fn.desc!!, decl))
      }
    }
    return out
  }

  /**
   * Returns whether the leaf class exposes a **public** no-argument constructor (`public Foo()`).
   *
   * @param internalName Class internal name.
   * @param roots Classpath containing the `.class` file.
   * @return True when `()V` `<init>` exists with `ACC_PUBLIC`.
   */
  fun hasPublicNoArgConstructor(internalName: String, roots: Collection<File>): Boolean {
    val bytes = loadClassBytes(internalName, roots) ?: return false
    val cn = ClassNode()
    ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG)
    for (m in cn.methods) {
      if (m.name != "<init>") {
        continue
      }
      if (m.desc != "()V") {
        continue
      }
      if ((m.access and Opcodes.ACC_PUBLIC) == 0) {
        continue
      }
      return true
    }
    return false
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
