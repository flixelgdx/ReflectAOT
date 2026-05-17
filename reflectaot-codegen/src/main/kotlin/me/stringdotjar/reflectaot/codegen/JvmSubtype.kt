package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File

/**
 * Minimal JVM subtype check used when generated `callMethod` dispatch must know whether a declaring type applies.
 *
 * This walks superclass links only. Implemented interfaces are ignored on purpose because ReflectAOT only needs a
 * conservative answer consistent with `invokevirtual` receiver checks against generated accessor classes.
 */
object JvmSubtype {

  /**
   * Returns true when [subInternal] is the same as [supInternal], or when [supInternal] appears on the superclass chain
   * above [subInternal]. [java/lang/Object] is treated as a universal supertype for this helper.
   *
   * @param subInternal Candidate subtype internal name.
   * @param supInternal Candidate supertype internal name.
   * @param roots Classpath roots used to read each class file while walking supers.
   * @return True when the subtype relation holds under the documented limitations.
   */
  fun isSubtype(subInternal: String, supInternal: String, roots: Collection<File>): Boolean {
    if (supInternal == "java/lang/Object") {
      return true
    }
    var cur: String? = subInternal
    while (cur != null && cur != "java/lang/Object") {
      if (cur == supInternal) {
        return true
      }
      cur = readSuperName(cur, roots)
    }
    return false
  }

  /**
   * Reads the `superName` field from the class file bytes for [internal].
   *
   * @param internal JVM internal name whose superclass is requested.
   * @param roots Classpath roots passed to [TypeIntrospection.loadClassBytes].
   * @return The superclass internal name, or null when bytes are missing or the reader cannot proceed.
   */
  private fun readSuperName(internal: String, roots: Collection<File>): String? {
    val bytes = TypeIntrospection.loadClassBytes(internal, roots) ?: return null
    val cn = ClassNode()
    ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG + ClassReader.SKIP_FRAMES)
    return cn.superName
  }
}
