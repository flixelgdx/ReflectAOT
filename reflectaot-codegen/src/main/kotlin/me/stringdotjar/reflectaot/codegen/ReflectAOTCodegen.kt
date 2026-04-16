package me.stringdotjar.reflectaot.codegen

import java.io.File

/** Entry point invoked by the Gradle task. */
object ReflectAOTCodegen {

  private val DEFAULT_EXCLUDES =
    listOf(
      "java.",
      "javax.",
      "kotlin.",
      "kotlinx.",
      "jdk.",
      "sun.",
      "com.sun.",
      "org.gradle.",
      "gradle.",
      "me.stringdotjar.reflectaot.generated.",
    )

  private val NEEDS_CONCRETE_RECEIVER =
    setOf(
      "field",
      "setField",
      "getProperty",
      "setProperty",
      "hasField",
      "fields",
      "methodId",
    )

  fun run(
    output: ReflectAOTOutput,
    bytecodeOutputDir: File,
    javaOutputDir: File,
    scanRoots: Collection<File>,
    excludePackagePrefixes: List<String>,
    extraTypes: List<String>,
  ) {
    val exclusions = (DEFAULT_EXCLUDES + excludePackagePrefixes).distinct()
    val scan = ReflectUsageScanner.scanClasspath(scanRoots, exclusions)

    val unresolvedReflect =
      scan.reflectCalls.filter { site ->
        site.reflectMethod in NEEDS_CONCRETE_RECEIVER && site.receiverInternalOrNull == null
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
        site.ownerClassInternalOrNull == null || site.nameOrNull == null || site.descriptorOrNull == null
      }
    if (unresolvedMethodIds.isNotEmpty()) {
      throw IllegalStateException(
        "ReflectAOT: Reflect.methodId(Class, String, String) must use a reference type .class literal " +
          "and two string literals so the build can resolve the method. " +
          "Primitive .class, dynamic Class values, or non-literal strings are not supported.",
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

  private fun validateReflectNames(
    scan: ClasspathScanResult,
    typesByInternal: Map<String, TypeIntrospection.IntrospectedType>,
  ) {
    for (site in scan.reflectCalls) {
      val recv = site.receiverInternalOrNull ?: continue
      val name = site.nameLiteralOrNull ?: continue
      val t = typesByInternal[recv] ?: continue
      when (site.reflectMethod) {
        "field", "setField" -> {
          if (!t.fields.containsKey(name)) {
            throw IllegalStateException(
              "ReflectAOT: unknown field \"$name\" on ${recv.replace('/', '.')} (from Reflect.${site.reflectMethod}). " +
                "Fix the name or add the correct receiver type to reflectaot.extraClasses.",
            )
          }
        }
        "getProperty", "setProperty", "hasField" -> {
          val propNames = t.properties.map { it.name }.toSet()
          val ok = t.fields.containsKey(name) || name in propNames
          if (!ok) {
            throw IllegalStateException(
              "ReflectAOT: unknown property or field \"$name\" on ${recv.replace('/', '.')} (from Reflect.${site.reflectMethod}). " +
                "For JavaBeans accessors use the property name (e.g. {@code x} for {@code getX()}). " +
                "To invoke a regular method use Reflect.methodId(SomeType.class, \"methodName\", \"(I)V\") and Reflect.callMethod(receiver, id, args).",
            )
          }
        }
      }
    }
  }

  private fun buildMethodBindings(
    scan: ClasspathScanResult,
    typesByInternal: Map<String, TypeIntrospection.IntrospectedType>,
  ): List<MethodIdBinding> {
    val keys = LinkedHashSet<Triple<String, String, String>>()
    for (s in scan.methodIdCalls) {
      val o = s.ownerClassInternalOrNull ?: continue
      val n = s.nameOrNull ?: continue
      val d = s.descriptorOrNull ?: continue
      keys.add(Triple(o, n, d))
    }
    val sortedKeys = keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    val out = ArrayList<MethodIdBinding>()
    var id = 0
    for ((o, n, d) in sortedKeys) {
      val t =
        typesByInternal[o]
          ?: throw IllegalStateException(
            "ReflectAOT: could not load class bytes for Reflect.methodId owner ${o.replace('/', '.')}.",
          )
      val im =
        t.instanceMethods.firstOrNull { it.name == n && it.descriptor == d }
          ?: throw IllegalStateException(
            "ReflectAOT: no public instance method $n$d on ${o.replace('/', '.')} for Reflect.methodId.",
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

  private fun emitBytecode(
    bytecodeOutputDir: File,
    types: List<TypeIntrospection.IntrospectedType>,
    roots: Collection<File>,
    methodBindings: List<MethodIdBinding>,
  ) {
    for (t in types) {
      AccessBytecodeEmitter.emit(t, bytecodeOutputDir, roots, methodBindings)
    }
    RegistryBytecodeEmitter.emit(types, bytecodeOutputDir)
    MethodIdTableBytecodeEmitter.emit(bytecodeOutputDir, methodBindings)
    BootstrapBytecodeEmitter.emit(bytecodeOutputDir)
  }

  private fun emitJava(
    javaOutputDir: File,
    types: List<TypeIntrospection.IntrospectedType>,
    methodBindings: List<MethodIdBinding>,
    roots: Collection<File>,
    emitBootstrap: Boolean,
  ) {
    JavaMirrorEmitter.emit(javaOutputDir, types, methodBindings, roots)
    MethodIdTableJavaEmitter.emit(javaOutputDir, methodBindings)
    if (emitBootstrap) {
      JavaBootstrapEmitter.emit(javaOutputDir)
    }
  }
}
