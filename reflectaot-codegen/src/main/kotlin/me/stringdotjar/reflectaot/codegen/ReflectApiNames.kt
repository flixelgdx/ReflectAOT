package me.stringdotjar.reflectaot.codegen

/**
 * JVM-visible **method names** and wiring constants for [me.stringdotjar.reflectaot.Reflect].
 *
 * The scanner matches **invokestatic** instructions where `owner` is [REFLECT_INTERNAL] and `name`
 * equals one of the `const val` entries below. If you rename a method in `Reflect.java`, update
 * this object (and any runtime messages) so scanning and generated bridges stay aligned.
 *
 * Note: obtaining a [me.stringdotjar.reflectaot.ReflectMethodId] uses the bytecode name **method**
 * (`Reflect.method(Class, …)`), not `methodId`.
 */
object ReflectApiNames {

  /** Internal name of `Reflect` as used in bytecode (`owner` for INVOKESTATIC). */
  const val REFLECT_INTERNAL = "me/stringdotjar/reflectaot/Reflect"
  const val FIELD = "field"
  const val SET_FIELD = "setField"
  const val PROPERTY = "property"
  const val SET_PROPERTY = "setProperty"
  const val HAS_FIELD = "hasField"
  const val FIELDS = "fields"
  const val CALL_METHOD = "callMethod"
  const val METHOD = "method"

  /**
   * Reflect calls where the scanner must infer a concrete reference receiver (not erased to
   * [java/lang/Object]) for codegen to specialize accessors.
   */
  val NEEDS_CONCRETE_RECEIVER: Set<String> =
    setOf(FIELD, SET_FIELD, PROPERTY, SET_PROPERTY, HAS_FIELD, FIELDS, METHOD)

  /**
   * All static `Reflect.*` call sites the scanner records (field/property dispatch, [METHOD], and
   * [CALL_METHOD]). Wider than [NEEDS_CONCRETE_RECEIVER]: `callMethod` does not require a
   * concrete specialized receiver at validation time.
   */
  val SCANNED_STATIC_NAMES: Set<String> = NEEDS_CONCRETE_RECEIVER + setOf(CALL_METHOD)

  /** Descriptor for `Reflect.method(Class, String, String)`. */
  const val METHOD_DESCRIPTOR_3_ARGS =
    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Lme/stringdotjar/reflectaot/ReflectMethodId;"

  /** Descriptor for `Reflect.method(Class, String)`. */
  const val METHOD_DESCRIPTOR_2_ARGS =
    "(Ljava/lang/Class;Ljava/lang/String;)Lme/stringdotjar/reflectaot/ReflectMethodId;"
}

/**
 * Default package prefixes skipped during classpath scanning (JDK, Kotlin stdlib, build tools,
 * generated output). Used by [ReflectAOTCodegen]; adjust via Gradle `reflectaot` excludes if needed.
 */
object ReflectClasspathScanDefaults {

  val PACKAGE_PREFIX_EXCLUDES: List<String> =
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
}
