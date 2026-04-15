package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.BasicVerifier
import org.objectweb.asm.tree.analysis.Frame
import java.io.File

/**
 * Discovers {@code me.stringdotjar.reflectaot.Reflect} static call sites and extracts the static JVM type of the
 * first {@code Object} receiver argument when it is known from bytecode (not {@code java/lang/Object}).
 */
object ReflectUsageScanner {
  private const val REFLECT_INTERNAL = "me/stringdotjar/reflectaot/Reflect"

  private val TRACKED =
    setOf(
      "field",
      "setField",
      "getProperty",
      "setProperty",
      "hasField",
      "fields",
    )

  data class CallSite(
    val receiverInternalOrNull: String?,
    val reflectMethod: String,
  )

  fun scanClasspath(
    roots: Collection<File>,
    excludePackagePrefixes: List<String>,
  ): List<CallSite> {
    val out = ArrayList<CallSite>()
    for (root in roots) {
      ClasspathWalker.forEachClassFile(root) { internal, bytes ->
        if (shouldExclude(internal, excludePackagePrefixes)) {
          return@forEachClassFile
        }
        out.addAll(scanClass(bytes))
      }
    }
    return out
  }

  fun shouldExclude(
    internalName: String,
    excludePackagePrefixes: List<String>,
  ): Boolean {
    val dotted = internalName.replace('/', '.')
    for (p in excludePackagePrefixes) {
      if (dotted.startsWith(p)) {
        return true
      }
    }
    return false
  }

  fun scanClass(bytes: ByteArray): List<CallSite> {
    val hits = ArrayList<CallSite>()
    val cn = ClassNode()
    val cr = org.objectweb.asm.ClassReader(bytes)
    cr.accept(cn, org.objectweb.asm.ClassReader.SKIP_DEBUG)
    for (m in cn.methods) {
      hits.addAll(scanMethod(cn.name, m))
    }
    return hits
  }

  private fun scanMethod(
    owner: String,
    method: MethodNode,
  ): List<CallSite> {
    val hits = ArrayList<CallSite>()
    val analyzer = Analyzer<BasicValue>(BasicVerifier())
    val frames: Array<Frame<BasicValue>?>
    try {
      @Suppress("UNCHECKED_CAST")
      frames = analyzer.analyze(owner, method) as Array<Frame<BasicValue>?>
    } catch (_: AnalyzerException) {
      return hits
    }
    val insns = method.instructions.toArray()
    for (i in insns.indices) {
      val insn = insns[i]
      if (insn !is MethodInsnNode) {
        continue
      }
      if (insn.opcode != Opcodes.INVOKESTATIC) {
        continue
      }
      if (insn.owner != REFLECT_INTERNAL) {
        continue
      }
      if (insn.name !in TRACKED) {
        continue
      }
      val frame = frames[i] ?: continue
      val argTypes = Type.getArgumentTypes(insn.desc)
      var words = 0
      for (t in argTypes.indices.reversed()) {
        words += argTypes[t].size
      }
      if (frame.stackSize < words) {
        continue
      }
      val receiverSlot = frame.stackSize - words
      val recv = frame.getStack(receiverSlot) ?: continue
      val recvType = recv.type ?: continue
      val sort = recvType.sort
      if (sort != Type.OBJECT && sort != Type.ARRAY) {
        continue
      }
      val internal = recvType.internalName
      if (internal == "java/lang/Object") {
        hits.add(CallSite(null, insn.name))
      } else {
        hits.add(CallSite(internal, insn.name))
      }
    }
    return hits
  }
}
