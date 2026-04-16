package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
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
    // Ignore StackMapTable and recompute frames from raw bytecode. OpenJ9 + large methods can
    // otherwise widen operand stack references to java/lang/Object in ASM's Analyzer.
    // Keep debug metadata (SKIP_DEBUG not set) so optional future heuristics can use locals.
    cr.accept(cn, org.objectweb.asm.ClassReader.SKIP_FRAMES)
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
        val refined =
          refineReceiverFromPrecedingBytecode(insns, i, insn)
            ?: "java/lang/Object"
        if (refined == "java/lang/Object") {
          hits.add(CallSite(null, insn.name))
        } else {
          hits.add(CallSite(refined, insn.name))
        }
      } else {
        hits.add(CallSite(internal, insn.name))
      }
    }
    return hits
  }

  /**
   * When ASM's dataflow merges the receiver to [java/lang/Object], recover a concrete reference
   * type from the immediately preceding bytecode for common `Reflect.*(recv, "name", ...)` shapes
   * emitted by javac (field read + string literal + boxed primitive literal).
   */
  private fun refineReceiverFromPrecedingBytecode(
    insns: Array<AbstractInsnNode>,
    invokeIndex: Int,
    reflectCall: MethodInsnNode,
  ): String? {
    var p = invokeIndex - 1
    p = skipNonInstructionsBackward(insns, p)
    if (p < 0) {
      return null
    }
    val argCount = Type.getArgumentTypes(reflectCall.desc).size
    when (argCount) {
      3 -> {
        p = stripTrailingBoxedIntLiteralBackward(insns, p)
        p = skipNonInstructionsBackward(insns, p)
        val two = insns.getOrNull(p)
        if (two !is LdcInsnNode || two.cst !is String) {
          return null
        }
        p--
      }
      2 -> {
        val two = insns.getOrNull(p)
        if (two is LdcInsnNode && two.cst is String) {
          p--
        }
      }
      1 -> {
        // single receiver arg only
      }
      else -> return null
    }
    p = skipNonInstructionsBackward(insns, p)
    val recvInsn = insns.getOrNull(p) ?: return null
    if (recvInsn is FieldInsnNode &&
      (recvInsn.opcode == Opcodes.GETFIELD || recvInsn.opcode == Opcodes.GETSTATIC)
    ) {
      val t = Type.getType(recvInsn.desc)
      if (t.sort == Type.OBJECT || t.sort == Type.ARRAY) {
        return t.internalName
      }
    }
    return null
  }

  private fun skipNonInstructionsBackward(
    insns: Array<AbstractInsnNode>,
    p: Int,
  ): Int {
    var q = p
    while (q >= 0) {
      val n = insns[q]
      if (n is LineNumberNode || n is FrameNode || n is LabelNode) {
        q--
      } else {
        break
      }
    }
    return q
  }

  private fun stripTrailingBoxedIntLiteralBackward(
    insns: Array<AbstractInsnNode>,
    p: Int,
  ): Int {
    if (p < 0) {
      return p
    }
    val top = insns[p]
    if (top is MethodInsnNode &&
      top.opcode == Opcodes.INVOKESTATIC &&
      top.owner == "java/lang/Integer" &&
      top.name == "valueOf" &&
      top.desc == "(I)Ljava/lang/Integer;"
    ) {
      var q = p - 1
      q = skipNonInstructionsBackward(insns, q)
      val lit = insns.getOrNull(q) ?: return p
      when {
        lit is IntInsnNode &&
          (lit.opcode == Opcodes.BIPUSH || lit.opcode == Opcodes.SIPUSH) -> {
          return q - 1
        }
        lit is InsnNode &&
          lit.opcode >= Opcodes.ICONST_M1 &&
          lit.opcode <= Opcodes.ICONST_5 -> {
          return q - 1
        }
        else -> return p
      }
    }
    if (top is LdcInsnNode && top.cst is Int) {
      return p - 1
    }
    return p
  }
}
