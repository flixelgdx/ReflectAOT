package me.stringdotjar.reflectaot.codegen

/**
 * User-facing exception text for generated `ReflectAOTMethodIdTable` bytecode and Java sources.
 *
 * Centralizes strings shared by [MethodIdTableBytecodeEmitter] and [MethodIdTableJavaEmitter] so wording stays aligned.
 */
object MethodIdTableMessages {

  private val m: String = ReflectApiNames.METHOD

  /**
   * Message when codegen produced no `Reflect.method` bindings but the resolver is still invoked.
   *
   * @return English explanation for `IllegalArgumentException` at runtime.
   */
  fun noReflectMethodCallSites(): String =
    """
    No Reflect.$m call sites were generated; remove Reflect.$m calls or run codegen after adding them.
    """.trimIndent().replace("\n", " ").trim()

  /**
   * Message when `resolve(Class, String, String)` does not match any generated binding.
   *
   * @return English explanation for `IllegalArgumentException` at runtime.
   */
  fun unknownReflectMethodTriple(): String =
    "Unknown Reflect.$m (class, name, descriptor) combination"

  /**
   * Message when `resolve(Class, String)` finds no matching overload name on the class.
   *
   * @return English explanation for `IllegalArgumentException` at runtime.
   */
  fun unknownReflectMethodClassAndName(): String =
    """
    Unknown Reflect.$m (class, name); use Reflect.$m(Class, String, String) with a JVM descriptor.
    """.trimIndent().replace("\n", " ").trim()

  /**
   * Message when `resolve(Class, String)` matches more than one overload for the same name.
   *
   * @return English explanation for `IllegalArgumentException` at runtime.
   */
  fun ambiguousReflectMethodClassAndName(): String =
    """
    Ambiguous Reflect.$m (class, name): multiple overloads share that name; use Reflect.$m(Class, String, String) with a JVM descriptor.
    """.trimIndent().replace("\n", " ").trim()

  /**
   * Escapes content for embedding inside a Java double-quoted string literal in generated sources.
   *
   * @param s Raw message text.
   * @return Text safe to concatenate between `\"` and `\"` in emitted Java.
   */
  fun escapeJavaStringLiteralContent(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
}
