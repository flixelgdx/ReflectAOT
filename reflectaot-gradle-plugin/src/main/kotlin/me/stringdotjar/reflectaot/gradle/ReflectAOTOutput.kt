package me.stringdotjar.reflectaot.gradle

import me.stringdotjar.reflectaot.codegen.ReflectAOTOutput as CodegenOutput

/**
 * Gradle DSL mirror of [me.stringdotjar.reflectaot.codegen.ReflectAOTOutput]. Default is [CLASS].
 */
enum class ReflectAOTOutput {

  CLASS,
  JAVA,
  BOTH,
  ;

  internal fun toCodegen(): CodegenOutput =
    when (this) {
      CLASS -> CodegenOutput.CLASS
      JAVA -> CodegenOutput.JAVA
      BOTH -> CodegenOutput.BOTH
    }
}
