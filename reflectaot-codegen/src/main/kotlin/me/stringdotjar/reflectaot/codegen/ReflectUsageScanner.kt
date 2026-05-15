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

/**
 * One scanned static `Reflect` call site.
 *
 * @property reflectMethod JVM name on [ReflectApiNames], for example `"field"` or `"callMethod"`.
 * @property receiverInternalOrNull Receiver type internal name when inferred, or null if erased to `java/lang/Object`.
 * @property nameLiteralOrNull Member name when resolved from a string `ldc` or a same-method `String` local bound to a supported constant chain; otherwise null (for example [ReflectApiNames.CALL_METHOD]).
 */
data class ReflectCallSite(
  val reflectMethod: String,
  val receiverInternalOrNull: String?,
  val nameLiteralOrNull: String?,
)

/**
 * Parsed operands for `Reflect.method(Class<?>, …)` near an `invokestatic`.
 *
 * @property ownerClassInternalOrNull JVM internal name from a class literal (`Foo` → `com/example/Foo`), or null when parsing failed.
 * @property nameOrNull Method name string literal, or null when parsing failed.
 * @property descriptorOrNull JVM method descriptor literal for the three-argument overload, or null for the two-argument overload (filled at codegen when unique).
 */
data class MethodIdCallSite(
  val ownerClassInternalOrNull: String?,
  val nameOrNull: String?,
  val descriptorOrNull: String?,
)

/**
 * Aggregate result of [ReflectUsageScanner.scanClasspath].
 *
 * @property reflectCalls Sites for field-like and dispatch APIs.
 * @property methodIdCalls Parsed `Reflect.method` sites.
 */
data class ClasspathScanResult(
  val reflectCalls: List<ReflectCallSite>,
  val methodIdCalls: List<MethodIdCallSite>,
)

/**
 * Walks compiled classes and finds `invokestatic` targets on [ReflectApiNames.REFLECT_INTERNAL].
 *
 * Field and property helpers record the API name and, when possible, the first-argument receiver type and a string literal member name.
 * For [ReflectApiNames.METHOD], operands are decoded from bytecode immediately before the call.
 */
object ReflectUsageScanner {

  /**
   * Scans every classpath root and aggregates all discovered sites.
   *
   * @param roots Directories (`build/classes/...`) and/or JAR files to walk.
   * @param excludePackagePrefixes Dotted prefixes; matching internal names are skipped (see [ReflectClasspathScanDefaults]).
   * @return Lists of reflect calls and method-id extractions merged from all roots.
   */
  fun scanClasspath(roots: Collection<File>, excludePackagePrefixes: List<String>): ClasspathScanResult {
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

  /**
   * Returns whether the given internal name falls under any exclude prefix.
   *
   * @param internalName JVM slash-separated internal class name (`com/example/Foo`).
   * @param excludePackagePrefixes Candidate dotted prefixes such as `"java."`.
   * @return True if scanning should skip this class entirely.
   */
  fun shouldExclude(internalName: String, excludePackagePrefixes: List<String>): Boolean {
    val dotted = internalName.replace('/', '.')
    for (p in excludePackagePrefixes) {
      if (dotted.startsWith(p)) {
        return true
      }
    }
    return false
  }

  /**
   * Parses a single `.class` file buffer for `Reflect` usages.
   *
   * Uses ASM’s [BasicVerifier]; if analysis fails for a method, that method contributes nothing (conservative).
   *
   * @param bytes Raw class file bytes.
   * @return Pair of reflect-call sites and `Reflect.method` sites for this class.
   */
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

  /**
   * Scans one method body for `Reflect` `invokestatic` instructions.
   *
   * @param owner Internal name of the class containing the method.
   * @param method ASM method node (instructions + frames implied by analysis).
   * @return Reflect-call sites and method-id sites found in this method.
   */
  private fun scanMethod(owner: String, method: MethodNode): Pair<List<ReflectCallSite>, List<MethodIdCallSite>> {
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
      if (insn.owner != ReflectApiNames.REFLECT_INTERNAL) {
        continue
      }
      if (insn.name !in ReflectApiNames.SCANNED_STATIC_NAMES) {
        continue
      }
      if (insn.name == ReflectApiNames.METHOD) {
        when (insn.desc) {
          ReflectApiNames.METHOD_DESCRIPTOR_3_ARGS -> methodIdHits.add(extractMethodIdThreeArgs(insns, i))
          ReflectApiNames.METHOD_DESCRIPTOR_2_ARGS -> methodIdHits.add(extractMethodIdTwoArgs(insns, i))
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
          refineReceiverFromPrecedingBytecode(insns, i, insn, method) ?: "java/lang/Object"
        } else {
          internal
        }
      val receiverOrNull = if (receiver == "java/lang/Object") null else receiver
      val nameLit =
        if (insn.name == ReflectApiNames.CALL_METHOD || insn.name == ReflectApiNames.FIELDS) {
          null
        } else {
          extractNameLiteral(method, insn, i, insns)
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

  /**
   * Reads the member name for two-argument field/property helpers, or for the name slot of three-argument setters
   * after stripping the assigned value expression.
   *
   * Accepts a string `ldc` or an `aload` of a local previously assigned from another supported name expression
   * in the same method (see [memberNameConstantString]).
   */
  private fun extractNameLiteral(
    method: MethodNode,
    reflectCall: MethodInsnNode,
    invokeIndex: Int,
    insns: Array<AbstractInsnNode>,
  ): String? {
    val argCount = Type.getArgumentTypes(reflectCall.desc).size
    return when (reflectCall.name) {
      ReflectApiNames.FIELD, ReflectApiNames.PROPERTY, ReflectApiNames.HAS_FIELD -> {
        if (argCount != 2) {
          return null
        }
        val p = insnIndexAboveInvoke(insns, invokeIndex)
        memberNameConstantString(method, insns, p)
      }
      ReflectApiNames.SET_FIELD, ReflectApiNames.SET_PROPERTY -> {
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
        memberNameConstantString(method, insns, p)
      }
      else -> null
    }
  }

  /**
   * Parses `(Class, String, String)` operands for `Reflect.method` (descriptor is the top stack slot before the name).
   *
   * @param insns Instruction array.
   * @param invokeIndex Index of the `invokestatic`.
   * @return A filled [MethodIdCallSite], or an all-null site when operands are not recognized.
   */
  private fun extractMethodIdThreeArgs(insns: Array<AbstractInsnNode>, invokeIndex: Int): MethodIdCallSite {
    var p = insnIndexAboveInvoke(insns, invokeIndex)
    val desc = ldcStringAt(insns, p) ?: return invalidMethodIdSite()
    p = moveToPreviousOperandStart(insns, p)
    val name = ldcStringAt(insns, p) ?: return invalidMethodIdSite()
    p = moveToPreviousOperandStart(insns, p)
    val classInsn = insns.getOrNull(p) ?: return invalidMethodIdSite()
    val owner = extractClassLiteralInternal(classInsn) ?: return invalidMethodIdSite()
    return MethodIdCallSite(owner, name, desc)
  }

  /**
   * Parses `(Class, String)` operands; the descriptor is resolved later at codegen when the name is unique.
   *
   * @param insns Instruction array.
   * @param invokeIndex Index of the `invokestatic`.
   * @return A site with [MethodIdCallSite.descriptorOrNull] set to null on success, or an all-null site on parse failure.
   */
  private fun extractMethodIdTwoArgs(insns: Array<AbstractInsnNode>, invokeIndex: Int): MethodIdCallSite {
    var p = insnIndexAboveInvoke(insns, invokeIndex)
    val name = ldcStringAt(insns, p) ?: return invalidMethodIdSite()
    p = moveToPreviousOperandStart(insns, p)
    val classInsn = insns.getOrNull(p) ?: return invalidMethodIdSite()
    val owner = extractClassLiteralInternal(classInsn) ?: return invalidMethodIdSite()
    return MethodIdCallSite(owner, name, null)
  }

  /** Sentinel when bytecode does not match the expected literal pattern. */
  private fun invalidMethodIdSite(): MethodIdCallSite = MethodIdCallSite(null, null, null)

  /**
   * Index of the first instruction above an `INVOKESTATIC`, after skipping line numbers, frames, and labels.
   *
   * @param insns Instruction array.
   * @param invokeIndex Index of the `invokestatic`.
   * @return Non-negative index, or negative if `invokeIndex` is zero (caller must still guard [getOrNull]).
   */
  private fun insnIndexAboveInvoke(insns: Array<AbstractInsnNode>, invokeIndex: Int): Int =
    skipNonInstructionsBackward(insns, invokeIndex - 1)

  /**
   * Decrements from the index of a consumed stack-producing instruction to the start index of the previous operand (skipping noise nodes).
   *
   * @param insns Instruction array.
   * @param consumedInsnIndex Index of the instruction that was just read as an operand.
   * @return Index positioned for the next operand toward the beginning of the method.
   */
  private fun moveToPreviousOperandStart(insns: Array<AbstractInsnNode>, consumedInsnIndex: Int): Int =
    skipNonInstructionsBackward(insns, consumedInsnIndex - 1)

  /**
   * String constant loaded by [LdcInsnNode] at [index], if present.
   *
   * @param insns Instruction array.
   * @param index Instruction index to read.
   * @return The [String] constant, or null if the slot is absent or not a string LDC.
   */
  private fun ldcStringAt(insns: Array<AbstractInsnNode>, index: Int): String? {
    val insn = insns.getOrNull(index) ?: return null
    return if (insn is LdcInsnNode && insn.cst is String) {
      insn.cst as String
    } else {
      null
    }
  }

  /** JVM local slot written by a normal [Opcodes.ASTORE] on [VarInsnNode], or null. Wide stores are not handled. */
  private fun aStoreVarSlot(insn: AbstractInsnNode): Int? =
    if (insn is VarInsnNode && insn.opcode == Opcodes.ASTORE) {
      insn.`var`
    } else {
      null
    }

  /**
   * Resolves the member-name string pushed for `Reflect.field` / `setField` / `property` / `setProperty` / `hasField`.
   *
   * Returns the [String] when the name expression is a string `ldc`, or an `aload` of a local that was assigned
   * from another such expression earlier in the same method (linear backward scan to the matching `astore`).
   */
  private fun memberNameConstantString(
    _method: MethodNode,
    insns: Array<AbstractInsnNode>,
    nameExprEndIndex: Int,
  ): String? {
    val visitingSlots = mutableSetOf<Int>()
    return expressionStringConstant(insns, nameExprEndIndex, 0, visitingSlots)
  }

  private fun expressionStringConstant(
    insns: Array<AbstractInsnNode>,
    exprEndIndex: Int,
    depth: Int,
    visitingSlots: MutableSet<Int>,
  ): String? {
    if (depth > 32) {
      return null
    }
    val p = skipNonInstructionsBackward(insns, exprEndIndex)
    if (p < 0) {
      return null
    }
    val insn = insns[p]
    return when {
      insn is LdcInsnNode && insn.cst is String ->
        insn.cst as String
      insn is VarInsnNode && insn.opcode == Opcodes.ALOAD ->
        localStringConstantBoundBeforeRead(insns, insn.`var`, p, depth + 1, visitingSlots)
      else -> null
    }
  }

  private fun localStringConstantBoundBeforeRead(
    insns: Array<AbstractInsnNode>,
    slot: Int,
    readInsnIndex: Int,
    depth: Int,
    visitingSlots: MutableSet<Int>,
  ): String? {
    if (depth > 32) {
      return null
    }
    if (!visitingSlots.add(slot)) {
      return null
    }
    try {
      var i = readInsnIndex - 1
      while (i >= 0) {
        i = skipNonInstructionsBackward(insns, i)
        if (i < 0) {
          return null
        }
        val insn = insns[i]
        if (aStoreVarSlot(insn) == slot) {
          return expressionStringConstant(insns, i - 1, depth + 1, visitingSlots)
        }
        i--
      }
      return null
    } finally {
      visitingSlots.remove(slot)
    }
  }

  /**
   * Resolves a [Type] LDC representing a reference or array class literal to an internal name.
   *
   * @param insn The instruction covering the class literal (typically `LDC` of [Type]).
   * @return Internal name such as `com/example/Foo`, or null if not a class literal.
   */
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
   * Recovers a concrete receiver type when the verifier merged the receiver slot to `java/lang/Object`.
   *
   * Mirrors [extractNameLiteral] stack stripping for three-argument setters so complex third operands
   * (constructors, boxed numerics, etc.) do not hide the member name. The name operand may be `ldc` or a traced
   * local (see [memberNameConstantString]). Also resolves [Opcodes.ALOAD] via [MethodNode.localVariables]
   * and [Opcodes.CHECKCAST] when present.
   */
  private fun refineReceiverFromPrecedingBytecode(
    insns: Array<AbstractInsnNode>,
    invokeIndex: Int,
    reflectCall: MethodInsnNode,
    containingMethod: MethodNode,
  ): String? {
    if (reflectCall.name == ReflectApiNames.CALL_METHOD) {
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
        p = stripOneExpressionBackward(insns, p)
        if (p < 0) {
          return null
        }
        p = skipNonInstructionsBackward(insns, p)
        if (memberNameConstantString(containingMethod, insns, p) == null) {
          return null
        }
        p = stripOneExpressionBackward(insns, p)
        if (p < 0) {
          return null
        }
      }
      2 -> {
        if (memberNameConstantString(containingMethod, insns, p) == null) {
          return null
        }
        p = stripOneExpressionBackward(insns, p)
        if (p < 0) {
          return null
        }
      }
      1 -> {}
      else -> return null
    }
    p = skipNonInstructionsBackward(insns, p)
    val recvInsn = insns.getOrNull(p) ?: return null
    return receiverInternalFromInsn(recvInsn, p, containingMethod)
  }

  /**
   * Maps the instruction that produced the Reflect call’s receiver operand to an internal type name.
   *
   * @param insn Instruction index [insnIndexInArray] in [containingMethod]’s instruction array.
   */
  private fun receiverInternalFromInsn(
    insn: AbstractInsnNode,
    insnIndexInArray: Int,
    containingMethod: MethodNode,
  ): String? {
    when {
      insn is FieldInsnNode &&
        (insn.opcode == Opcodes.GETFIELD || insn.opcode == Opcodes.GETSTATIC) -> {
        val t = Type.getType(insn.desc)
        if (t.sort == Type.OBJECT || t.sort == Type.ARRAY) {
          return t.internalName
        }
      }
      insn is VarInsnNode && insn.opcode == Opcodes.ALOAD -> {
        val desc = localVariableDescriptorAt(containingMethod, insnIndexInArray, insn.`var`)
        if (desc != null) {
          val t = Type.getType(desc)
          if (t.sort == Type.OBJECT || t.sort == Type.ARRAY) {
            return t.internalName
          }
        }
      }
      insn is TypeInsnNode && insn.opcode == Opcodes.CHECKCAST -> {
        return Type.getObjectType(insn.desc).internalName
      }
    }
    return null
  }

  /** Resolves the JVM field descriptor for a local slot active at [insnIndexInArray]. */
  private fun localVariableDescriptorAt(method: MethodNode, insnIndexInArray: Int, localSlot: Int): String? {
    val arr = method.instructions.toArray()
    for (lv in method.localVariables ?: emptyList()) {
      if (lv.index != localSlot) {
        continue
      }
      val startIdx = arr.indexOf(lv.start)
      val endIdx = arr.indexOf(lv.end)
      if (startIdx < 0 || endIdx < 0) {
        continue
      }
      if (insnIndexInArray >= startIdx && insnIndexInArray < endIdx) {
        return lv.desc
      }
    }
    return null
  }

  /**
   * Walks backward over line-number, stack-map frame, and label nodes only.
   *
   * @param insns Instruction array.
   * @param p Starting index (inclusive).
   * @return The nearest index at or before [p] that references a non-metadata instruction, or negative if exhausted.
   */
  private fun skipNonInstructionsBackward(insns: Array<AbstractInsnNode>, p: Int): Int {
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

  /**
   * Steps backward across one JVM stack operand (constant, load, simple field read tail, etc.).
   *
   * @param insns Instruction array.
   * @param p Index of the instruction that produced the top-of-stack value used by the following instruction.
   * @return Index after removing that contribution, or -1 if the pattern is not recognized.
   */
  private fun stripOneExpressionBackward(insns: Array<AbstractInsnNode>, p: Int): Int {
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
        if (op == Opcodes.I2F || op == Opcodes.I2D || op == Opcodes.I2L || op == Opcodes.I2B || op == Opcodes.I2C || op == Opcodes.I2S) {
          var q = p - 1
          q = skipNonInstructionsBackward(insns, q)
          return stripOneExpressionBackward(insns, q)
        }
        if (op == Opcodes.F2I || op == Opcodes.F2L || op == Opcodes.F2D) {
          var q = p - 1
          q = skipNonInstructionsBackward(insns, q)
          return stripOneExpressionBackward(insns, q)
        }
        if (op == Opcodes.L2I || op == Opcodes.L2F || op == Opcodes.L2D) {
          var q = p - 1
          q = skipNonInstructionsBackward(insns, q)
          return stripOneExpressionBackward(insns, q)
        }
        if (op == Opcodes.D2I || op == Opcodes.D2F || op == Opcodes.D2L) {
          var q = p - 1
          q = skipNonInstructionsBackward(insns, q)
          return stripOneExpressionBackward(insns, q)
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
        when {
          insn.opcode == Opcodes.INVOKESTATIC &&
            insn.owner == "java/lang/Integer" &&
            insn.name == "valueOf" &&
            insn.desc == "(I)Ljava/lang/Integer;" ->
            return stripTrailingBoxedIntLiteralBackward(insns, p)
          insn.opcode == Opcodes.INVOKESTATIC &&
            insn.owner == "java/lang/Float" &&
            insn.name == "valueOf" &&
            insn.desc == "(F)Ljava/lang/Float;" ->
            return stripTrailingBoxedFloatLiteralBackward(insns, p)
          insn.opcode == Opcodes.INVOKESTATIC &&
            insn.owner == "java/lang/Double" &&
            insn.name == "valueOf" &&
            insn.desc == "(D)Ljava/lang/Double;" ->
            return stripTrailingBoxedDoubleLiteralBackward(insns, p)
          insn.opcode == Opcodes.INVOKESTATIC &&
            insn.owner == "java/lang/Long" &&
            insn.name == "valueOf" &&
            insn.desc == "(J)Ljava/lang/Long;" ->
            return stripTrailingBoxedLongLiteralBackward(insns, p)
          insn.opcode == Opcodes.INVOKESPECIAL && insn.name == "<init>" ->
            return stripConstructorInvocationBackward(insns, p)
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

  /**
   * Strips `new T(args…)` / `new T()` so the member-name operand can be read under the value expression.
   *
   * Expects javac-shaped `NEW`, `DUP`, …, `INVOKESPECIAL <init>`.
   */
  private fun stripConstructorInvocationBackward(insns: Array<AbstractInsnNode>, invokeSpecialIndex: Int): Int {
    val insn = insns.getOrNull(invokeSpecialIndex) as? MethodInsnNode ?: return -1
    if (insn.opcode != Opcodes.INVOKESPECIAL || insn.name != "<init>") {
      return -1
    }
    val argTypes = Type.getArgumentTypes(insn.desc)
    var q = invokeSpecialIndex - 1
    repeat(argTypes.size) {
      q = skipNonInstructionsBackward(insns, q)
      q = stripOneExpressionBackward(insns, q)
      if (q < 0) {
        return -1
      }
    }
    q = skipNonInstructionsBackward(insns, q)
    val dupInsn = insns.getOrNull(q)
    if (dupInsn is InsnNode && dupInsn.opcode == Opcodes.DUP) {
      var r = q - 1
      r = skipNonInstructionsBackward(insns, r)
      val newInsn = insns.getOrNull(r)
      if (newInsn is TypeInsnNode && newInsn.opcode == Opcodes.NEW) {
        return r - 1
      }
      return -1
    }
    if (argTypes.isEmpty()) {
      val newInsn = insns.getOrNull(q)
      if (newInsn is TypeInsnNode && newInsn.opcode == Opcodes.NEW) {
        return q - 1
      }
    }
    return -1
  }

  private fun stripTrailingBoxedFloatLiteralBackward(insns: Array<AbstractInsnNode>, p: Int): Int {
    val top = insns.getOrNull(p) ?: return -1
    if (top !is MethodInsnNode ||
      top.opcode != Opcodes.INVOKESTATIC ||
      top.owner != "java/lang/Float" ||
      top.name != "valueOf" ||
      top.desc != "(F)Ljava/lang/Float;"
    ) {
      return -1
    }
    var q = p - 1
    q = skipNonInstructionsBackward(insns, q)
    val lit = insns.getOrNull(q) ?: return -1
    when {
      lit is LdcInsnNode && lit.cst is Float -> return q - 1
      lit is InsnNode &&
        (lit.opcode == Opcodes.FCONST_0 || lit.opcode == Opcodes.FCONST_1 || lit.opcode == Opcodes.FCONST_2) -> return q - 1
      lit is InsnNode && lit.opcode == Opcodes.I2F -> {
        var r = q - 1
        r = skipNonInstructionsBackward(insns, r)
        return stripOneExpressionBackward(insns, r)
      }
      else -> return -1
    }
  }

  private fun stripTrailingBoxedDoubleLiteralBackward(insns: Array<AbstractInsnNode>, p: Int): Int {
    val top = insns.getOrNull(p) ?: return -1
    if (top !is MethodInsnNode ||
      top.opcode != Opcodes.INVOKESTATIC ||
      top.owner != "java/lang/Double" ||
      top.name != "valueOf" ||
      top.desc != "(D)Ljava/lang/Double;"
    ) {
      return -1
    }
    var q = p - 1
    q = skipNonInstructionsBackward(insns, q)
    val lit = insns.getOrNull(q) ?: return -1
    when {
      lit is LdcInsnNode && lit.cst is Double -> return q - 1
      lit is InsnNode &&
        (lit.opcode == Opcodes.DCONST_0 || lit.opcode == Opcodes.DCONST_1) -> return q - 1
      lit is InsnNode && (lit.opcode == Opcodes.I2D || lit.opcode == Opcodes.F2D) -> {
        var r = q - 1
        r = skipNonInstructionsBackward(insns, r)
        return stripOneExpressionBackward(insns, r)
      }
      else -> return -1
    }
  }

  private fun stripTrailingBoxedLongLiteralBackward(insns: Array<AbstractInsnNode>, p: Int): Int {
    val top = insns.getOrNull(p) ?: return -1
    if (top !is MethodInsnNode ||
      top.opcode != Opcodes.INVOKESTATIC ||
      top.owner != "java/lang/Long" ||
      top.name != "valueOf" ||
      top.desc != "(J)Ljava/lang/Long;"
    ) {
      return -1
    }
    var q = p - 1
    q = skipNonInstructionsBackward(insns, q)
    val lit = insns.getOrNull(q) ?: return -1
    when {
      lit is LdcInsnNode && lit.cst is Long -> return q - 1
      lit is InsnNode &&
        (lit.opcode == Opcodes.LCONST_0 || lit.opcode == Opcodes.LCONST_1) -> return q - 1
      lit is InsnNode && lit.opcode == Opcodes.I2L -> {
        var r = q - 1
        r = skipNonInstructionsBackward(insns, r)
        return stripOneExpressionBackward(insns, r)
      }
      else -> return -1
    }
  }

  /**
   * Removes a trailing `Integer.valueOf` / primitive int literal pair when walking backward from setter call sites.
   *
   * @param insns Instruction array.
   * @param p Current index (often pointing at `valueOf` or an int literal).
   * @return Adjusted index after stripping that suffix, or [p] when no boxed-int tail is found.
   */
  private fun stripTrailingBoxedIntLiteralBackward(insns: Array<AbstractInsnNode>, p: Int): Int {
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
