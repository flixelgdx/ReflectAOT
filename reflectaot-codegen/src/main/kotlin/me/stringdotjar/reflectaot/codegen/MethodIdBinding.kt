package me.stringdotjar.reflectaot.codegen

/**
 * One build-resolved `Reflect.method` call site after overload selection and id assignment.
 *
 * @property id Stable integer token surfaced as [me.stringdotjar.reflectaot.ReflectMethodId] at runtime.
 * @property userClassInternal Internal name of the class literal operand as seen at the call site (slashes).
 * @property name JVM method name string resolved from bytecode.
 * @property descriptor JVM method descriptor string resolved from bytecode or inferred for the two-argument overload.
 * @property declaringInternal Internal name of the class that actually declares the selected public instance method.
 */
data class MethodIdBinding(
  val id: Int,
  val userClassInternal: String,
  val name: String,
  val descriptor: String,
  val declaringInternal: String,
)
