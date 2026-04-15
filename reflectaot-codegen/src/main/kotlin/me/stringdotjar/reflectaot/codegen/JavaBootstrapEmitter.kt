package me.stringdotjar.reflectaot.codegen

import java.io.File

/** Emits Java source for a bootstrap that installs {@link me.stringdotjar.reflectaot.generated.ReflectAOTRegistry}. */
object JavaBootstrapEmitter {

  fun emit(outputDir: File) {
    outputDir.mkdirs()
    val pkg = File(outputDir, "me/stringdotjar/reflectaot/generated")
    pkg.mkdirs()
    val f = File(pkg, "ReflectAOTBootstrap.java")
    f.writeText(
      """
      package me.stringdotjar.reflectaot.generated;

      import me.stringdotjar.reflectaot.ReflectAOTServices;

      public final class ReflectAOTBootstrap {

          static {
              ReflectAOTServices.install(new ReflectAOTRegistry());
          }

          private ReflectAOTBootstrap() {
          }
      }
      """.trimIndent() + "\n",
    )
  }
}
