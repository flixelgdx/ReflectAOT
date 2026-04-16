package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File

/** JVM assignability {@code sub <: sup} using only super-class edges (ignores interfaces). */
object JvmSubtype {

  fun isSubtype(
    subInternal: String,
    supInternal: String,
    roots: Collection<File>,
  ): Boolean {
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

  private fun readSuperName(
    internal: String,
    roots: Collection<File>,
  ): String? {
    val bytes = TypeIntrospection.loadClassBytes(internal, roots) ?: return null
    val cn = ClassNode()
    ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG + ClassReader.SKIP_FRAMES)
    return cn.superName
  }
}
