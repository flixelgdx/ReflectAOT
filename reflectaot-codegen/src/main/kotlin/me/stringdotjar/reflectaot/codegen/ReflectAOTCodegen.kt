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
    val callsites = ReflectUsageScanner.scanClasspath(scanRoots, exclusions)

    val unresolved =
      callsites.filter { site ->
        site.reflectMethod in NEEDS_CONCRETE_RECEIVER && site.receiverInternalOrNull == null
      }
    if (unresolved.isNotEmpty()) {
      val sample = unresolved.take(5).joinToString { it.reflectMethod }
      throw IllegalStateException(
        "ReflectAOT: cannot infer concrete receiver types for some Reflect calls ($sample). " +
          "Narrow the receiver type in bytecode (casts or locals) or add reflectaot { extraClasses.add(\"com.example.Concrete\") }.",
      )
    }

    val internals = LinkedHashSet<String>()
    for (c in callsites) {
      c.receiverInternalOrNull?.let { internals.add(it) }
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

    bytecodeOutputDir.mkdirs()
    javaOutputDir.mkdirs()

    when (output) {
      ReflectAOTOutput.CLASS -> emitBytecode(bytecodeOutputDir, types, scanRoots)
      ReflectAOTOutput.JAVA -> emitJava(javaOutputDir, types, emitBootstrap = true)
      ReflectAOTOutput.BOTH -> {
        emitBytecode(bytecodeOutputDir, types, scanRoots)
        emitJava(javaOutputDir, types, emitBootstrap = false)
      }
    }
  }

  private fun emitBytecode(
    bytecodeOutputDir: File,
    types: List<TypeIntrospection.IntrospectedType>,
    roots: Collection<File>,
  ) {
    for (t in types) {
      AccessBytecodeEmitter.emit(t, bytecodeOutputDir, roots)
    }
    RegistryBytecodeEmitter.emit(types, bytecodeOutputDir)
    BootstrapBytecodeEmitter.emit(bytecodeOutputDir)
  }

  private fun emitJava(
    javaOutputDir: File,
    types: List<TypeIntrospection.IntrospectedType>,
    emitBootstrap: Boolean,
  ) {
    JavaMirrorEmitter.emit(javaOutputDir, types)
    if (emitBootstrap) {
      JavaBootstrapEmitter.emit(javaOutputDir)
    }
  }
}
