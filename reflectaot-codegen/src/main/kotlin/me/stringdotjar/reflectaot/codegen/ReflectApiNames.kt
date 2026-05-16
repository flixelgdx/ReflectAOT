package me.stringdotjar.reflectaot.codegen

/**
 * JVM-visible method names and wiring constants for [me.stringdotjar.reflectaot.Reflect].
 *
 * The scanner matches `invokestatic` instructions where `owner` is [REFLECT_INTERNAL] and `name` equals one of the
 * `const val` entries below. If you rename a method in `Reflect.java`, update this object so scanning and generated
 * bridges stay aligned.
 *
 * Obtaining a [me.stringdotjar.reflectaot.ReflectMethodId] uses the bytecode name [METHOD] (`Reflect.method(Class, ...)`),
 * not `methodId`.
 */
object ReflectApiNames {

  /** Slash-separated internal name used as `owner` for each `Reflect` `invokestatic` (`me/stringdotjar/reflectaot/Reflect`). */
  const val REFLECT_INTERNAL = "me/stringdotjar/reflectaot/Reflect"

  /** Matches `Reflect.field`. */
  const val FIELD = "field"

  /** Matches `Reflect.setField`. */
  const val SET_FIELD = "setField"

  /** Matches `Reflect.property`. */
  const val PROPERTY = "property"

  /** Matches `Reflect.setProperty`. */
  const val SET_PROPERTY = "setProperty"

  /** Matches `Reflect.hasField`. */
  const val HAS_FIELD = "hasField"

  /** Matches `Reflect.fields`. */
  const val FIELDS = "fields"

  /** Matches `Reflect.forEachField`. */
  const val FOR_EACH_FIELD = "forEachField"

  /** Matches `Reflect.forEachProperty`. */
  const val FOR_EACH_PROPERTY = "forEachProperty"

  /** Matches `Reflect.forEachMethod`. */
  const val FOR_EACH_METHOD = "forEachMethod"

  /** JVM descriptor for `Reflect.forEachField(Object, BiConsumer)`. */
  const val FOR_EACH_FIELD_DESCRIPTOR = "(Ljava/lang/Object;Ljava/util/function/BiConsumer;)V"

  /** JVM descriptor for `Reflect.forEachProperty(Object, BiConsumer)`. */
  const val FOR_EACH_PROPERTY_DESCRIPTOR = "(Ljava/lang/Object;Ljava/util/function/BiConsumer;)V"

  /** JVM descriptor for `Reflect.forEachMethod(Class, BiConsumer)`. */
  const val FOR_EACH_METHOD_DESCRIPTOR = "(Ljava/lang/Class;Ljava/util/function/BiConsumer;)V"

  /** Matches `Reflect.callMethod`. */
  const val CALL_METHOD = "callMethod"

  /** Matches `Reflect.copy`. */
  const val COPY = "copy"

  /** Matches `Reflect.method` for build-time method identity (must stay aligned with `Reflect.java`). */
  const val METHOD = "method"

  /**
   * API names for which the build must infer a concrete reference receiver (not only `java/lang/Object`).
   *
   * Used by [ReflectAOTCodegen] when validating [ReflectCallSite] entries.
   */
  val NEEDS_CONCRETE_RECEIVER: Set<String> =
    setOf(
      FIELD,
      SET_FIELD,
      PROPERTY,
      SET_PROPERTY,
      HAS_FIELD,
      FIELDS,
      METHOD,
      FOR_EACH_FIELD,
      FOR_EACH_PROPERTY,
      FOR_EACH_METHOD,
    )

  /**
   * APIs whose member name must be a string **constant** in bytecode so codegen can validate it.
   *
   * Used by [me.stringdotjar.reflectaot.codegen.ReflectAOTCodegen]; excludes [FIELDS] (no name) and
   * [METHOD] (handled via [MethodIdCallSite]).
   */
  val NEEDS_COMPILE_TIME_MEMBER_NAME_LITERAL: Set<String> =
    setOf(FIELD, SET_FIELD, PROPERTY, SET_PROPERTY, HAS_FIELD)

  /**
   * All `Reflect` static targets the scanner walks (includes [CALL_METHOD], which does not appear in [NEEDS_CONCRETE_RECEIVER]).
   */
  val SCANNED_STATIC_NAMES: Set<String> = NEEDS_CONCRETE_RECEIVER + setOf(CALL_METHOD)

  /** JVM descriptor for `Reflect.method(Class, String, String)` (return type [me.stringdotjar.reflectaot.ReflectMethodId]). */
  const val METHOD_DESCRIPTOR_3_ARGS =
    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Lme/stringdotjar/reflectaot/ReflectMethodId;"

  /** JVM descriptor for `Reflect.method(Class, String)` (return type [me.stringdotjar.reflectaot.ReflectMethodId]). */
  const val METHOD_DESCRIPTOR_2_ARGS =
    "(Ljava/lang/Class;Ljava/lang/String;)Lme/stringdotjar/reflectaot/ReflectMethodId;"
}

/**
 * Default package prefixes skipped during classpath scanning (JDK, Kotlin stdlib, build tools,
 * generated output). Used by [ReflectAOTCodegen]; adjust via Gradle `reflectaot` excludes if needed.
 */
object ReflectClasspathScanDefaults {

  /** Dotted prefixes applied after converting internal names from slashes to dots. Merged with user excludes before [ReflectUsageScanner.scanClasspath]. */
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
