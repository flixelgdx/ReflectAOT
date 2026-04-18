package me.stringdotjar.reflectaot.codegen

import java.io.File

/**
 * Orchestrates the build: scan → load types → validate → emit bytecode and/or Java mirrors.
 *
 * Invoked from the Gradle plugin with outputs and scan roots derived from compile classpaths.
 */
object ReflectAOTCodegen {

  /**
   * Loads every discovered receiver/`Reflect.method` owner type, validates names against
   * introspection, binds [ReflectMethodId]s, then delegates to bytecode and/or Java emitters.
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
        "ReflectAOT: cannot infer concrete receiver types for some Reflect calls ($sample). " +
          "Narrow the receiver type in bytecode (casts or locals) or add reflectaot { extraClasses.add(\"com.example.Concrete\") }.",
      )
    }

    val unresolvedMethodIds =
      scan.methodIdCalls.filter { site ->
        site.ownerClassInternalOrNull == null || site.nameOrNull == null
      }
    if (unresolvedMethodIds.isNotEmpty()) {
      throw IllegalStateException(
        "ReflectAOT: Reflect.${ReflectApiNames.METHOD}(...) must use a reference type .class literal and string literal(s) " +
          "so the build can resolve the method. Primitive .class or non-constant arguments are not supported.",
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
            "ReflectAOT: could not load class bytes for $internal from scan inputs. " +
              "Ensure it is compiled before generateReflectAOT runs.",
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
   * Ensures literal field/property names seen at call sites exist on the introspected receiver type.
   *
   * Does not validate `Reflect.method` here — that happens in [buildMethodBindings].
   */
  private fun validateReflectNames(scan: ClasspathScanResult, typesByInternal: Map<String, TypeIntrospection.IntrospectedType>) {
    for (site in scan.reflectCalls) {
      val recv = site.receiverInternalOrNull ?: continue
      val name = site.nameLiteralOrNull ?: continue
      val t = typesByInternal[recv] ?: continue
      when (site.reflectMethod) {
        ReflectApiNames.FIELD, ReflectApiNames.SET_FIELD -> {
          if (!t.fields.containsKey(name)) {
            throw IllegalStateException(
              "ReflectAOT: unknown field \"$name\" on ${recv.replace('/', '.')} (from Reflect.${site.reflectMethod}). " +
                "Fix the name or add the correct receiver type to reflectaot.extraClasses.",
            )
          }
        }
        ReflectApiNames.PROPERTY, ReflectApiNames.SET_PROPERTY, ReflectApiNames.HAS_FIELD -> {
          val propNames = t.properties.map { it.name }.toSet()
          val ok = t.fields.containsKey(name) || name in propNames
          if (!ok) {
            throw IllegalStateException(
              "ReflectAOT: unknown property or field \"$name\" on ${recv.replace('/', '.')} (from Reflect.${site.reflectMethod}). " +
                "For JavaBeans accessors use the property name (e.g. {@code foo} for {@code getFoo()}). " +
                "To invoke a regular method use Reflect.${ReflectApiNames.METHOD}(SomeType.class, \"methodName\") when the name is unique, or Reflect.${ReflectApiNames.METHOD}(SomeType.class, \"methodName\", \"(I)V\") with a JVM descriptor if overloaded.",
            )
          }
        }
      }
    }
  }

  /**
   * Dedupes `(owner, method name, JVM descriptor)` pairs from scanned `Reflect.method` calls,
   * resolves missing descriptors when only two args were used and the method name is unique,
   * assigns stable numeric ids used by `ReflectMethodId` and dispatch tables.
   */
  private fun buildMethodBindings(scan: ClasspathScanResult, typesByInternal: Map<String, TypeIntrospection.IntrospectedType>): List<MethodIdBinding> {
    val keys = LinkedHashSet<Triple<String, String, String>>()
    for (s in scan.methodIdCalls) {
      val o = s.ownerClassInternalOrNull ?: continue
      val n = s.nameOrNull ?: continue
      val t =
        typesByInternal[o]
          ?: throw IllegalStateException(
            "ReflectAOT: could not load class bytes for Reflect.${ReflectApiNames.METHOD} owner ${o.replace('/', '.')}.",
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
                "ReflectAOT: no public instance method named \"$n\" on ${o.replace('/', '.')} for Reflect.${ReflectApiNames.METHOD}(Class, String).",
              )
            1 -> candidates[0].descriptor
            else ->
              throw IllegalStateException(
                "ReflectAOT: method \"$n\" on ${o.replace('/', '.')} is overloaded; cannot use Reflect.${ReflectApiNames.METHOD}(Class, String). " +
                  "Overloads: ${candidates.joinToString { it.descriptor }}. " +
                  "Use Reflect.${ReflectApiNames.METHOD}(${o.replace('/', '.')}.class, \"$n\", \"<descriptor>\") with the JVM descriptor of the overload you need.",
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
            "ReflectAOT: could not load class bytes for Reflect.${ReflectApiNames.METHOD} owner ${o.replace('/', '.')}.",
          )
      val im =
        t.instanceMethods.firstOrNull { it.name == n && it.descriptor == d }
          ?: throw IllegalStateException(
            "ReflectAOT: no public instance method $n$d on ${o.replace('/', '.')} for Reflect.${ReflectApiNames.METHOD}.",
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

  private fun emitBytecode(bytecodeOutputDir: File, types: List<TypeIntrospection.IntrospectedType>, roots: Collection<File>, methodBindings: List<MethodIdBinding>) {
    for (t in types) {
      AccessBytecodeEmitter.emit(t, bytecodeOutputDir, roots, methodBindings)
    }
    RegistryBytecodeEmitter.emit(types, bytecodeOutputDir)
    MethodIdTableBytecodeEmitter.emit(bytecodeOutputDir, methodBindings)
    BootstrapBytecodeEmitter.emit(bytecodeOutputDir)
  }

  /** When output is BOTH, bootstrap Java is omitted because bytecode path already installs services. */
  private fun emitJava(javaOutputDir: File, types: List<TypeIntrospection.IntrospectedType>, methodBindings: List<MethodIdBinding>, roots: Collection<File>, emitBootstrap: Boolean) {
    JavaMirrorEmitter.emit(javaOutputDir, types, methodBindings, roots)
    MethodIdTableJavaEmitter.emit(javaOutputDir, methodBindings)
    if (emitBootstrap) {
      JavaBootstrapEmitter.emit(javaOutputDir)
    }
  }
}
