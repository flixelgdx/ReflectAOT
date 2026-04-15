package me.stringdotjar.reflectaot.codegen

/**
 * What the codegen pipeline writes. Default is [CLASS] (ASM `.class` only).
 */
enum class ReflectAOTOutput {

  /** Bytecode only (TeaVM / MobiVM / javac). */
  CLASS,

  /** Generated Java sources (e.g. GWT). */
  JAVA,

  /** Emit bytecode and Java sources from the same model. */
  BOTH,
}
