package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.Opcodes
import java.io.File

/**
 * Build-time entry point that ties scanning, validation, and emission together.
 *
 * Typical pipeline:
 *
 * 1. [ReflectUsageScanner.scanClasspath] walks every scan root and records [ReflectCallSite] and [MethodIdCallSite] rows.
 * 2. Preconditions fail fast when receivers, member names, or `Reflect.method` operands cannot be resolved conservatively.
 * 3. [TypeIntrospection.load] loads each referenced JVM type from the same roots used for scanning.
 * 4. [validateReflectNames] checks field-like `Reflect` calls against introspected metadata.
 * 5. [buildMethodBindings] assigns stable ids for `Reflect.method` targets, including overload resolution for the two-argument form.
 * 6. Emitters write bytecode and/or Java sources under the configured output directories.
 *
 * The Gradle plugin calls [run] from `generateReflectAOT`. Other build integrations may call it directly.
 */
object ReflectAOTCodegen {

  private fun dotted(internal: String): String = internal.replace('/', '.')

  /** Collapses a trim-indented multiline template into one line for [IllegalStateException] messages. */
  private fun oneLine(multiline: String): String =
    multiline.trimIndent().replace("\n", " ").trim()

  /**
   * Executes the full ReflectAOT pipeline for the given scan roots and writes generated artifacts.
   *
   * @param output Whether to emit bytecode, Java sources, or both (see [ReflectAOTOutput]).
   * @param bytecodeOutputDir Directory that will receive generated `.class` files when bytecode emission runs.
   * @param javaOutputDir Directory that will receive generated `.java` files when Java emission runs.
   * @param scanRoots Compiled project outputs plus dependency jars or directories to scan and to load types from.
   * @param excludePackagePrefixes Extra dotted package prefixes merged with [ReflectClasspathScanDefaults.PACKAGE_PREFIX_EXCLUDES] before scanning.
   * @param extraClasses Fully qualified names that must receive accessors even when no call site references them.
   * @throws IllegalStateException When scan results are inconsistent with ReflectAOT rules, when referenced types cannot be loaded, or when validation fails.
   */
  fun run(output: ReflectAOTOutput, bytecodeOutputDir: File, javaOutputDir: File, scanRoots: Collection<File>, excludePackagePrefixes: List<String>, extraTypes: List<String>) {
    val exclusions = (ReflectClasspathScanDefaults.PACKAGE_PREFIX_EXCLUDES + excludePackagePrefixes).distinct()
    val scan = ReflectUsageScanner.scanClasspath(scanRoots, exclusions)

    val unresolvedReflect =
      scan.reflectCalls.filter { site ->
        site.reflectMethod in ReflectApiNames.NEEDS_CONCRETE_RECEIVER && site.receiverInternalOrNull == null
      }
    if (unresolvedReflect.isNotEmpty()) {
      val sample = unresolvedReflect.take(5).joinToString { it.reflectMethod }
      throw IllegalStateException(
        oneLine(
          """
          ReflectAOT: cannot infer concrete receiver types for some Reflect calls ($sample).
          Narrow the receiver type in bytecode (casts or locals) or add reflectaot { extraClasses.add("com.example.Concrete") }.
          """,
        ),
      )
    }

    val unresolvedMethodIds =
      scan.methodIdCalls.filter { site ->
        site.ownerClassInternalOrNull == null || site.nameOrNull == null
      }
    if (unresolvedMethodIds.isNotEmpty()) {
      throw IllegalStateException(
        oneLine(
          """
          ReflectAOT: Reflect.${ReflectApiNames.METHOD}(...) must use a reference type class operand and string operands
          that resolve as literals or same-method locals bound to supported constant chains so the build can resolve the method.
          Primitive class tokens, parameters, concatenation, and other non-constant forms are not supported.
          """,
        ),
      )
    }

    val unresolvedMemberNameLiterals =
      scan.reflectCalls.filter { site ->
        site.reflectMethod in ReflectApiNames.NEEDS_COMPILE_TIME_MEMBER_NAME_LITERAL &&
          site.receiverInternalOrNull != null &&
          site.nameLiteralOrNull == null
      }
    if (unresolvedMemberNameLiterals.isNotEmpty()) {
      val sample =
        unresolvedMemberNameLiterals.take(5).joinToString { "${it.reflectMethod}(...)" }
      throw IllegalStateException(
        oneLine(
          """
          ReflectAOT: member name must be a string literal or a String local assigned from a supported constant
          expression in the same method for Reflect.field, setField, property, setProperty, and hasField
          so the build can validate names ($sample).
          Method parameters, string concatenation, and other non-constant forms are not supported.
          """,
        ),
      )
    }

    val internals = LinkedHashSet<String>()
    for (c in scan.reflectCalls) {
      c.receiverInternalOrNull?.let { internals.add(it) }
    }
    for (m in scan.methodIdCalls) {
      m.ownerClassInternalOrNull?.let { internals.add(it) }
    }
    for (extra in extraTypes) {
      internals.add(extra.replace('.', '/'))
    }

    val types = ArrayList<TypeIntrospection.IntrospectedType>()
    for (internal in internals) {
      val loaded =
        TypeIntrospection.load(internal, scanRoots)
          ?: throw IllegalStateException(
            oneLine(
              """
              ReflectAOT: could not load class bytes for $internal from scan inputs.
              Ensure it is compiled before generateReflectAOT runs.
              """,
            ),
          )
      types.add(loaded)
    }

    val typesByInternal = types.associateBy { it.internalName }
    validateReflectNames(scan, typesByInternal)
    val methodBindings = buildMethodBindings(scan, typesByInternal)

    bytecodeOutputDir.mkdirs()
    javaOutputDir.mkdirs()

    when (output) {
      ReflectAOTOutput.CLASS -> emitBytecode(bytecodeOutputDir, types, scanRoots, methodBindings)
      ReflectAOTOutput.JAVA -> emitJava(javaOutputDir, types, methodBindings, scanRoots, emitBootstrap = true)
      ReflectAOTOutput.BOTH -> {
        emitBytecode(bytecodeOutputDir, types, scanRoots, methodBindings)
        emitJava(javaOutputDir, types, methodBindings, scanRoots, emitBootstrap = false)
      }
    }
  }

  /**
   * Ensures field-like `Reflect` call sites reference members that exist on the introspected receiver type.
   *
   * @param scan Aggregated output from [ReflectUsageScanner.scanClasspath].
   * @param typesByInternal Map from JVM internal name to loaded [TypeIntrospection.IntrospectedType] instances.
   * @throws IllegalStateException When a referenced name is missing, not visible, or violates setter or final-field rules enforced by ReflectAOT for field-like APIs other than `hasField` (which treats unknown names as false at runtime).
   */
  private fun validateReflectNames(scan: ClasspathScanResult, typesByInternal: Map<String, TypeIntrospection.IntrospectedType>) {
    for (site in scan.reflectCalls) {
      val recv = site.receiverInternalOrNull ?: continue
      val recvDotted = dotted(recv)
      val name = site.nameLiteralOrNull ?: continue
      val t = typesByInternal[recv] ?: continue
      when (site.reflectMethod) {
        ReflectApiNames.FIELD -> {
          val meta =
            t.instanceFieldsMeta[name]
              ?: throw IllegalStateException(
                oneLine(
                  """
                  ReflectAOT: unknown field "$name" on $recvDotted (from Reflect.${site.reflectMethod}).
                  Fix the name or add the correct receiver type to reflectaot.extraClasses.
                  """,
                ),
              )
          if ((meta.access and Opcodes.ACC_PUBLIC) == 0) {
            throw IllegalStateException(
              oneLine(
                """
                ReflectAOT: Reflect.${site.reflectMethod} cannot access "$name" on $recvDotted:
                field is ${TypeIntrospection.visibilityWord(meta.access)}.
                Only public instance fields are supported for direct field access; use Reflect.property(...) / Reflect.setProperty(...) with JavaBeans accessors when the member is not public.
                """,
              ),
            )
          }
        }
        ReflectApiNames.SET_FIELD -> {
          val meta =
            t.instanceFieldsMeta[name]
              ?: throw IllegalStateException(
                oneLine(
                  """
                  ReflectAOT: unknown field "$name" on $recvDotted (from Reflect.${site.reflectMethod}).
                  Fix the name or add the correct receiver type to reflectaot.extraClasses.
                  """,
                ),
              )
          if ((meta.access and Opcodes.ACC_PUBLIC) == 0) {
            throw IllegalStateException(
              oneLine(
                """
                ReflectAOT: Reflect.${site.reflectMethod} cannot access "$name" on $recvDotted:
                field is ${TypeIntrospection.visibilityWord(meta.access)}.
                Only public instance fields can be assigned via Reflect.${ReflectApiNames.SET_FIELD}; use Reflect.${ReflectApiNames.SET_PROPERTY} with a setter when the field is not public.
                """,
              ),
            )
          }
          if ((meta.access and Opcodes.ACC_FINAL) != 0) {
            if (ReflectPropertyAnalysis.canWritePropertyName(t, name)) {
              throw IllegalStateException(
                oneLine(
                  """
                  ReflectAOT: Reflect.${site.reflectMethod} cannot assign final field "$name" on $recvDotted.
                  Use Reflect.${ReflectApiNames.SET_PROPERTY}(...) instead. A public setter or another writable path exists.
                  """,
                ),
              )
            }
            throw IllegalStateException(
              oneLine(
                """
                ReflectAOT: Reflect.${site.reflectMethod} cannot assign "$name" on $recvDotted: field is final.
                """,
              ),
            )
          }
        }
        ReflectApiNames.PROPERTY -> {
          if (!ReflectPropertyAnalysis.canReadPropertyName(t, name)) {
            throw IllegalStateException(
              oneLine(
                """
                ReflectAOT: no readable property or public field "$name" on $recvDotted (from Reflect.${site.reflectMethod}).
                Use a JavaBeans getter name, or only public fields for direct reads.
                """,
              ),
            )
          }
        }
        ReflectApiNames.SET_PROPERTY -> {
          if (!ReflectPropertyAnalysis.canWritePropertyName(t, name)) {
            throw IllegalStateException(
              oneLine(
                """
                ReflectAOT: no writable property or non-final public field "$name" on $recvDotted (from Reflect.${site.reflectMethod}).
                Final fields and fields without a public setter cannot be written via Reflect.${site.reflectMethod}.
                """,
              ),
            )
          }
        }
      }
    }
  }

  /**
   * Builds stable [MethodIdBinding] rows for every distinct `Reflect.method` triple seen during scanning.
   *
   * @param scan Aggregated scan output containing [ClasspathScanResult.methodIdCalls].
   * @param typesByInternal Map from JVM internal name to loaded introspection models for owners referenced by method ids.
   * @return Ordered list of bindings with dense integer ids used by generated dispatch and [ReflectMethodId] tokens.
   * @throws IllegalStateException When an owner type cannot be loaded, when no overload matches, when overloads are ambiguous for the two-argument form, or when the resolved method is not a supported public instance method.
   */
  private fun buildMethodBindings(scan: ClasspathScanResult, typesByInternal: Map<String, TypeIntrospection.IntrospectedType>): List<MethodIdBinding> {
    val keys = LinkedHashSet<Triple<String, String, String>>()
    for (s in scan.methodIdCalls) {
      val o = s.ownerClassInternalOrNull ?: continue
      val n = s.nameOrNull ?: continue
      val t =
        typesByInternal[o]
          ?: throw IllegalStateException(
            "ReflectAOT: could not load class bytes for Reflect.${ReflectApiNames.METHOD} owner ${dotted(o)}.",
          )
      val d =
        if (s.descriptorOrNull != null) {
          s.descriptorOrNull
        } else {
          // Two-arg Reflect.method(Class, String): pick the sole public overload, or fail with overload list.
          val candidates = t.instanceMethods.filter { it.name == n }
          when (candidates.size) {
            0 ->
              throw IllegalStateException(
                "ReflectAOT: no public instance method named \"$n\" on ${dotted(o)} for Reflect.${ReflectApiNames.METHOD}(Class, String).",
              )
            1 -> candidates[0].descriptor
            else ->
              throw IllegalStateException(
                oneLine(
                  """
                  ReflectAOT: method "$n" on ${dotted(o)} is overloaded; cannot use Reflect.${ReflectApiNames.METHOD}(Class, String).
                  Overloads: ${candidates.joinToString { it.descriptor }}.
                  Use Reflect.${ReflectApiNames.METHOD}(${dotted(o)}.class, "$n", "<descriptor>") with the JVM descriptor of the overload you need.
                  """,
                ),
              )
          }
        }
      keys.add(Triple(o, n, d))
    }
    val sortedKeys = keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    val out = ArrayList<MethodIdBinding>()
    var id = 0
    for ((o, n, d) in sortedKeys) {
      val t =
        typesByInternal[o]
          ?: throw IllegalStateException(
            "ReflectAOT: could not load class bytes for Reflect.${ReflectApiNames.METHOD} owner ${dotted(o)}.",
          )
      val im =
        t.instanceMethods.firstOrNull { it.name == n && it.descriptor == d }
          ?: throw IllegalStateException(
            "ReflectAOT: no public instance method $n$d on ${dotted(o)} for Reflect.${ReflectApiNames.METHOD}.",
          )
      out.add(
        MethodIdBinding(
          id = id++,
          userClassInternal = o,
          name = n,
          descriptor = d,
          declaringInternal = im.declaringInternal,
        ),
      )
    }
    return out
  }

  /**
   * Emits bytecode artifacts for accessors, registry, method id table, and bootstrap classes.
   *
   * @param bytecodeOutputDir Root directory for generated `.class` files.
   * @param types Introspected receiver types that require specialized accessors.
   * @param roots Classpath roots passed to emitters that need subtype checks against compiled bytecode.
   * @param methodBindings Resolved `Reflect.method` bindings produced by [buildMethodBindings].
   */
  private fun emitBytecode(bytecodeOutputDir: File, types: List<TypeIntrospection.IntrospectedType>, roots: Collection<File>, methodBindings: List<MethodIdBinding>) {
    for (t in types) {
      AccessBytecodeEmitter.emit(t, bytecodeOutputDir, roots, methodBindings)
    }
    RegistryBytecodeEmitter.emit(types, bytecodeOutputDir, methodBindings)
    MethodIdTableBytecodeEmitter.emit(bytecodeOutputDir, methodBindings)
    BootstrapBytecodeEmitter.emit(bytecodeOutputDir)
  }

  /**
   * Emits Java source mirrors for accessors, registry, optional bootstrap, and the method id table.
   *
   * @param javaOutputDir Root directory that will receive generated `.java` files.
   * @param types Introspected receiver types that require specialized accessors.
   * @param methodBindings Resolved `Reflect.method` bindings.
   * @param roots Classpath roots forwarded to emitters that need subtype checks.
   * @param emitBootstrap When false, skips [JavaBootstrapEmitter] because bytecode output already installs services (used for BOTH mode).
   */
  private fun emitJava(javaOutputDir: File, types: List<TypeIntrospection.IntrospectedType>, methodBindings: List<MethodIdBinding>, roots: Collection<File>, emitBootstrap: Boolean) {
    JavaMirrorEmitter.emit(javaOutputDir, types, methodBindings, roots)
    MethodIdTableJavaEmitter.emit(javaOutputDir, methodBindings)
    if (emitBootstrap) {
      JavaBootstrapEmitter.emit(javaOutputDir)
    }
  }
}
