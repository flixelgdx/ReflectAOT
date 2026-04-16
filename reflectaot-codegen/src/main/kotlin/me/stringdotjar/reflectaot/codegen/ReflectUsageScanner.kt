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
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.BasicVerifier
import org.objectweb.asm.tree.analysis.Frame
import java.io.File

data class ReflectCallSite(
  val reflectMethod: String,
  val receiverInternalOrNull: String?,
  val nameLiteralOrNull: String?,
)

data class MethodIdCallSite(
  val ownerClassInternalOrNull: String?,
  val nameOrNull: String?,
  val descriptorOrNull: String?,
)

data class ClasspathScanResult(
  val reflectCalls: List<ReflectCallSite>,
  val methodIdCalls: List<MethodIdCallSite>,
)

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
      "callMethod",
      "methodId",
    )

  private const val METHOD_ID_DESC = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Lme/stringdotjar/reflectaot/ReflectMethodId;"

  fun scanClasspath(
    roots: Collection<File>,
    excludePackagePrefixes: List<String>,
  ): ClasspathScanResult {
    val reflectCalls = ArrayList<ReflectCallSite>()
    val methodIdCalls = ArrayList<MethodIdCallSite>()
    for (root in roots) {
      ClasspathWalker.forEachClassFile(root) { internal, bytes ->
        if (shouldExclude(internal, excludePackagePrefixes)) {
          return@forEachClassFile
        }
        val (r, m) = scanClass(bytes)
        reflectCalls.addAll(r)
        methodIdCalls.addAll(m)
      }
    }
    return ClasspathScanResult(reflectCalls, methodIdCalls)
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

  fun scanClass(bytes: ByteArray): Pair<List<ReflectCallSite>, List<MethodIdCallSite>> {
    val reflectHits = ArrayList<ReflectCallSite>()
    val methodIdHits = ArrayList<MethodIdCallSite>()
    val cn = ClassNode()
    val cr = org.objectweb.asm.ClassReader(bytes)
    cr.accept(cn, org.objectweb.asm.ClassReader.SKIP_FRAMES)
    for (m in cn.methods) {
      val (r, mid) = scanMethod(cn.name, m)
      reflectHits.addAll(r)
      methodIdHits.addAll(mid)
    }
    return reflectHits to methodIdHits
  }

  private fun scanMethod(
    owner: String,
    method: MethodNode,
  ): Pair<List<ReflectCallSite>, List<MethodIdCallSite>> {
    val reflectHits = ArrayList<ReflectCallSite>()
    val methodIdHits = ArrayList<MethodIdCallSite>()
    val analyzer = Analyzer<BasicValue>(BasicVerifier())
    val frames: Array<Frame<BasicValue>?>
    try {
      @Suppress("UNCHECKED_CAST")
      frames = analyzer.analyze(owner, method) as Array<Frame<BasicValue>?>
    } catch (_: AnalyzerException) {
      return reflectHits to methodIdHits
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
      if (insn.name == "methodId") {
        if (insn.desc == METHOD_ID_DESC) {
          methodIdHits.add(extractMethodIdLiterals(insns, i))
        }
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
      val receiver =
        if (internal == "java/lang/Object") {
          refineReceiverFromPrecedingBytecode(insns, i, insn) ?: "java/lang/Object"
        } else {
          internal
        }
      val receiverOrNull = if (receiver == "java/lang/Object") null else receiver
      val nameLit =
        if (insn.name == "callMethod" || insn.name == "fields") {
          null
        } else {
          extractNameLiteral(insn, i, insns)
        }
      reflectHits.add(
        ReflectCallSite(
          reflectMethod = insn.name,
          receiverInternalOrNull = receiverOrNull,
          nameLiteralOrNull = nameLit,
        ),
      )
    }
    return reflectHits to methodIdHits
  }

  private fun extractNameLiteral(
    reflectCall: MethodInsnNode,
    invokeIndex: Int,
    insns: Array<AbstractInsnNode>,
  ): String? {
    val argCount = Type.getArgumentTypes(reflectCall.desc).size
    return when (reflectCall.name) {
      "field", "getProperty", "hasField" -> {
        if (argCount != 2) {
          return null
        }
        var p = invokeIndex - 1
        p = skipNonInstructionsBackward(insns, p)
        val insn = insns.getOrNull(p) ?: return null
        if (insn is LdcInsnNode && insn.cst is String) {
          insn.cst as String
        } else {
          null
        }
      }
      "setField", "setProperty" -> {
        if (argCount != 3) {
          return null
        }
        var p = invokeIndex - 1
        p = skipNonInstructionsBackward(insns, p)
        p = stripOneExpressionBackward(insns, p)
        if (p < 0) {
          return null
        }
        p = skipNonInstructionsBackward(insns, p)
        val insn = insns.getOrNull(p) ?: return null
        if (insn is LdcInsnNode && insn.cst is String) {
          insn.cst as String
        } else {
          null
        }
      }
      else -> null
    }
  }

  private fun extractMethodIdLiterals(
    insns: Array<AbstractInsnNode>,
    invokeIndex: Int,
  ): MethodIdCallSite {
    var p = invokeIndex - 1
    p = skipNonInstructionsBackward(insns, p)
    val dIns = insns.getOrNull(p)
    if (dIns !is LdcInsnNode || dIns.cst !is String) {
      return MethodIdCallSite(null, null, null)
    }
    val desc = dIns.cst as String
    p--
    p = skipNonInstructionsBackward(insns, p)
    val nIns = insns.getOrNull(p)
    if (nIns !is LdcInsnNode || nIns.cst !is String) {
      return MethodIdCallSite(null, null, null)
    }
    val name = nIns.cst as String
    p--
    p = skipNonInstructionsBackward(insns, p)
    val cIns = insns.getOrNull(p) ?: return MethodIdCallSite(null, null, null)
    val owner = extractClassLiteralInternal(cIns)
    if (owner == null) {
      return MethodIdCallSite(null, null, null)
    }
    return MethodIdCallSite(owner, name, desc)
  }

  private fun extractClassLiteralInternal(insn: AbstractInsnNode): String? {
    if (insn is LdcInsnNode) {
      val cst = insn.cst
      if (cst is Type && (cst.sort == Type.OBJECT || cst.sort == Type.ARRAY)) {
        return cst.internalName
      }
    }
    return null
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
    if (reflectCall.name == "callMethod") {
      return null
    }
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

  private fun stripOneExpressionBackward(
    insns: Array<AbstractInsnNode>,
    p: Int,
  ): Int {
    if (p < 0) {
      return -1
    }
    val insn = insns[p]
    when (insn) {
      is LdcInsnNode -> return p - 1
      is InsnNode -> {
        val op = insn.opcode
        if (op in Opcodes.ICONST_M1..Opcodes.ICONST_5) {
          return p - 1
        }
        if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) {
          return p - 1
        }
        if (op == Opcodes.FCONST_0 || op == Opcodes.FCONST_1 || op == Opcodes.FCONST_2) {
          return p - 1
        }
        if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) {
          return p - 1
        }
        if (op == Opcodes.ACONST_NULL) {
          return p - 1
        }
      }
      is IntInsnNode -> {
        if (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) {
          return p - 1
        }
      }
      is VarInsnNode -> {
        val op = insn.opcode
        if (op in Opcodes.ILOAD..Opcodes.ALOAD) {
          return p - 1
        }
      }
      is FieldInsnNode -> {
        if (insn.opcode == Opcodes.GETSTATIC && insn.desc == "Ljava/lang/Class;" && insn.name == "TYPE") {
          return p - 1
        }
        if (insn.opcode == Opcodes.GETFIELD) {
          var q = p - 1
          q = skipNonInstructionsBackward(insns, q)
          return stripOneExpressionBackward(insns, q)
        }
      }
      is MethodInsnNode -> {
        if (insn.opcode == Opcodes.INVOKESTATIC &&
          insn.owner == "java/lang/Integer" &&
          insn.name == "valueOf" &&
          insn.desc == "(I)Ljava/lang/Integer;"
        ) {
          return stripTrailingBoxedIntLiteralBackward(insns, p - 1)
        }
      }
      is TypeInsnNode -> {
        if (insn.opcode == Opcodes.NEW) {
          return p - 1
        }
      }
    }
    return -1
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
