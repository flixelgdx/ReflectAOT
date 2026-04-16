package me.stringdotjar.reflectaot.codegen

/** One build-resolved entry for {@code Reflect.methodId} and generated {@code callMethod} dispatch. */
data class MethodIdBinding(
  val id: Int,
  val userClassInternal: String,
  val name: String,
  val descriptor: String,
  val declaringInternal: String,
)
