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
 * Parsed operands for `Reflect.method(Class, String, ...)` near an `invokestatic`.
 *
 * @property ownerClassInternalOrNull JVM internal name for the owner class operand when it resolves as a constant class literal or supported local chain, or null when parsing failed.
 * @property nameOrNull Method name when it resolves as a constant string or supported local chain, or null when parsing failed.
 * @property descriptorOrNull JVM method descriptor string for the three-argument overload when it resolves as a constant or supported local chain, or null for the two-argument overload (filled at codegen when unique).
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
 * ASM-driven scanner for static `Reflect` entry points in compiled bytecode.
 *
 * Responsibilities:
 *
 * - Locate `invokestatic` calls whose owner is [ReflectApiNames.REFLECT_INTERNAL] and whose name is in [ReflectApiNames.SCANNED_STATIC_NAMES].
 * - For field-like APIs, use ASM stack frames when possible to recover the receiver reference type, and decode member names using [memberNameConstantString].
 * - For `Reflect.method`, decode class and string operands using [memberClassLiteral] and [memberNameConstantString], walking the same instruction array backward from each call.
 * - When the verifier merges a receiver to `java/lang/Object`, optionally refine the receiver using a conservative backward walk before the call.
 *
 * Wide local slots and non-linear control flow are intentionally out of scope. Failed frame analysis causes the enclosing method to contribute no sites.
 */
object ReflectUsageScanner {

  /**
   * Scans every classpath root and aggregates all discovered sites.
   *
   * @param roots Directories such as `build/classes/java/main` and/or JAR files to walk.
   * @param excludePackagePrefixes Dotted package prefixes. Matching internal names are skipped (see [ReflectClasspathScanDefaults]).
   * @return Merged [ReflectCallSite] and [MethodIdCallSite] lists from every readable class file under the roots.
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
   * Returns whether a JVM internal class name should be skipped based on configured package excludes.
   *
   * @param internalName Slash-separated internal class name such as `com/example/Foo`.
   * @param excludePackagePrefixes Dotted prefixes such as `java.`.
   * @return True when the internal name maps to a package that starts with any exclude prefix.
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
   * Uses ASM [BasicVerifier] frame analysis. If analysis fails for a method, that method contributes no sites.
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
   * Scans one method body for `Reflect` `invokestatic` instructions and collects matching call sites.
   *
   * Frame analysis is required for non-`Reflect.method` calls so receiver types can be read from the operand stack.
   * `Reflect.method` decoding uses backward instruction walks instead of frames for operand extraction.
   *
   * @param owner Internal name of the class that declares [method].
   * @param method ASM method node including instructions and debug metadata used by local variable helpers.
   * @return Pair of reflect-call sites and method-id sites discovered in this method body.
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
          ReflectApiNames.METHOD_DESCRIPTOR_3_ARGS -> methodIdHits.add(extractMethodIdThreeArgs(method, insns, i))
          ReflectApiNames.METHOD_DESCRIPTOR_2_ARGS -> methodIdHits.add(extractMethodIdTwoArgs(method, insns, i))
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
        memberNameConstantString(insns, p)
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
        memberNameConstantString(insns, p)
      }
      else -> null
    }
  }

  /**
   * Parses `(Class, String, String)` operands for `Reflect.method`. The descriptor is the top stack slot before the name.
   *
   * @param method The enclosing method (used for local tracing on class and string operands).
   * @param insns Instruction array for that method.
   * @param invokeIndex Index of the `invokestatic` for `Reflect.method`.
   * @return A filled [MethodIdCallSite], or an all-null site when operands are not recognized.
   */
  private fun extractMethodIdThreeArgs(method: MethodNode, insns: Array<AbstractInsnNode>, invokeIndex: Int): MethodIdCallSite {
    var p = insnIndexAboveInvoke(insns, invokeIndex)
    val desc = memberNameConstantString(insns, p) ?: return invalidMethodIdSite()
    p = moveToPreviousOperandStart(insns, p)
    val name = memberNameConstantString(insns, p) ?: return invalidMethodIdSite()
    p = moveToPreviousOperandStart(insns, p)
    val owner = memberClassLiteral(method, insns, p) ?: return invalidMethodIdSite()
    return MethodIdCallSite(owner, name, desc)
  }

  /**
   * Parses `(Class, String)` operands. The descriptor is resolved later at codegen when the name is unique.
   *
   * @param method The enclosing method (used for local string tracing on the name operand).
   * @param insns Instruction array for that method.
   * @param invokeIndex Index of the `invokestatic` for `Reflect.method`.
   * @return A site with [MethodIdCallSite.descriptorOrNull] set to null on success, or an all-null site on parse failure.
   */
  private fun extractMethodIdTwoArgs(method: MethodNode, insns: Array<AbstractInsnNode>, invokeIndex: Int): MethodIdCallSite {
    var p = insnIndexAboveInvoke(insns, invokeIndex)
    val name = memberNameConstantString(insns, p) ?: return invalidMethodIdSite()
    p = moveToPreviousOperandStart(insns, p)
    val owner = memberClassLiteral(method, insns, p) ?: return invalidMethodIdSite()
    return MethodIdCallSite(owner, name, null)
  }

  /**
   * Sentinel value used when `Reflect.method` operand decoding fails so callers can ignore the row during aggregation.
   *
   * @return A [MethodIdCallSite] whose properties are all null.
   */
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

  /**
   * Returns the local variable index written by a normal `astore` instruction.
   *
   * @param insn Any instruction node from the method instruction list.
   * @return The slot index when [insn] is a [VarInsnNode] with opcode [Opcodes.ASTORE], otherwise null.
   */
  private fun aStoreVarSlot(insn: AbstractInsnNode): Int? =
    if (insn is VarInsnNode && insn.opcode == Opcodes.ASTORE) {
      insn.`var`
    } else {
      null
    }

  /**
   * Resolves a compile-time string used as a field or property member name, or as a `Reflect.method` name or descriptor operand.
   *
   * @param insns Instruction array for that method.
   * @param nameExprEndIndex Index of the last instruction that contributes the string value on the operand stack.
   * @return The constant string when the operand is an `ldc` string or a supported same-method local chain, otherwise null.
   */
  private fun memberNameConstantString(
    insns: Array<AbstractInsnNode>,
    nameExprEndIndex: Int,
  ): String? {
    val visitingSlots = mutableSetOf<Int>()
    return expressionStringConstant(insns, nameExprEndIndex, 0, visitingSlots)
  }

  /**
   * Resolves a string operand from a single expression tail, including `ldc` strings and `aload` locals.
   *
   * @param insns Instruction array for the enclosing method.
   * @param exprEndIndex Index of the last instruction that contributes the string value.
   * @param depth Current recursion depth for chained locals (capped internally).
   * @param visitingSlots Slot set used to detect cycles while resolving locals.
   * @return The resolved string constant, or null when the pattern is not supported.
   */
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

  /**
   * Resolves a string local by walking backward to the most recent `astore` that defines the slot.
   *
   * @param insns Instruction array for the enclosing method.
   * @param slot Local variable index read by a string `aload`.
   * @param readInsnIndex Instruction index of the `aload`.
   * @param depth Current recursion depth for chained locals.
   * @param visitingSlots Slot visitation set shared for one top-level string resolution.
   * @return The resolved string constant, or null when no matching assignment exists or the assignment is not constant.
   */
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
   * Resolves the `Class` operand for `Reflect.method` when it denotes a reference or array type.
   *
   * Accepts a class `ldc`, a `GETSTATIC` `SomeType.class` handle for a reference type, or an `aload` of a local
   * previously assigned from another supported class expression in the same method.
   *
   * @param method The enclosing method (reserved for future local variable table use).
   * @param insns Instruction array for that method.
   * @param exprEndIndex Index of the last instruction that contributes the class operand.
   * @return JVM internal name for the class operand, or null when not resolved.
   */
  private fun memberClassLiteral(
    @Suppress("UNUSED_PARAMETER") method: MethodNode,
    insns: Array<AbstractInsnNode>,
    exprEndIndex: Int,
  ): String? {
    val visitingSlots = mutableSetOf<Int>()
    return expressionClassConstant(insns, exprEndIndex, 0, visitingSlots)
  }

  /**
   * Resolves a compile-time class operand for `Reflect.method`, including `ldc` types, `aload` locals, and reference `SomeType.class` handles.
   *
   * @param insns Instruction array for the enclosing method.
   * @param exprEndIndex Index of the last instruction that contributes the class value.
   * @param depth Current recursion depth for chained locals.
   * @param visitingSlots Slot set used to detect cycles while resolving locals.
   * @return JVM internal name for the class operand, or null when the pattern is not supported.
   */
  private fun expressionClassConstant(
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
    extractClassLiteralInternal(insn)?.let { return it }
    if (insn is VarInsnNode && insn.opcode == Opcodes.ALOAD) {
      return localClassConstantBoundBeforeRead(insns, insn.`var`, p, depth + 1, visitingSlots)
    }
    if (insn is FieldInsnNode &&
      insn.opcode == Opcodes.GETSTATIC &&
      insn.desc == "Ljava/lang/Class;" &&
      insn.name == "class"
    ) {
      return insn.owner
    }
    return null
  }

  /**
   * Resolves a class local by walking backward to the most recent `astore` that defines the slot.
   *
   * @param insns Instruction array for the enclosing method.
   * @param slot Local variable index read by a class `aload`.
   * @param readInsnIndex Instruction index of the `aload`.
   * @param depth Current recursion depth for chained locals.
   * @param visitingSlots Slot visitation set shared for one top-level `memberClassLiteral` resolution.
   * @return JVM internal name when the stored value is provably constant, or null.
   */
  private fun localClassConstantBoundBeforeRead(
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
        val st = insns[i]
        if (aStoreVarSlot(st) == slot) {
          return expressionClassConstant(insns, i - 1, depth + 1, visitingSlots)
        }
        i--
      }
      return null
    } finally {
      visitingSlots.remove(slot)
    }
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
        if (memberNameConstantString(insns, p) == null) {
          return null
        }
        p = stripOneExpressionBackward(insns, p)
        if (p < 0) {
          return null
        }
      }
      2 -> {
        if (memberNameConstantString(insns, p) == null) {
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
   * Maps the instruction that produced the Reflect call receiver operand to an internal type name.
   *
   * @param insn Instruction at the receiver expression tail.
   * @param insnIndexInArray Index of [insn] inside [containingMethod] instruction array.
   * @param containingMethod Method that owns the instruction array.
   * @return JVM internal name for a reference or array receiver, or null when the pattern is not recognized.
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

  /**
   * Looks up the JVM type descriptor for a local variable slot at a specific instruction index using [MethodNode.localVariables].
   *
   * @param method Method whose local variable table is consulted.
   * @param insnIndexInArray Instruction index in [method] where the read occurs.
   * @param localSlot Local variable index used by an `aload` or related opcode.
   * @return Field-style descriptor for the active local, or null when no table entry covers the index.
   */
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
   * Steps backward across one JVM stack operand, so later logic can read the previous operand toward the start of the method.
   *
   * Recognizes constants, local loads, simple field read tails, common boxed numeric tails, and `new` plus `invokespecial` shapes.
   *
   * @param insns Instruction array for the enclosing method.
   * @param p Index of the instruction that produced the consumed stack value.
   * @return Index of the instruction just before the removed contribution, or -1 when the pattern is not recognized.
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
   * Strips `new T(args...)` / `new T()` so the member-name operand can be read under the value expression.
   *
   * Expects javac-shaped `NEW`, `DUP`, then `INVOKESPECIAL <init>`.
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
