package me.stringdotjar.reflectaot.codegen

/**
 * One build-resolved `Reflect.method(...)` call site: stable id, declaring type for `invokevirtual`,
 * and JVM descriptor string for overload resolution.
 */
data class MethodIdBinding(
  val id: Int,
  val userClassInternal: String,
  val name: String,
  val descriptor: String,
  val declaringInternal: String,
)
