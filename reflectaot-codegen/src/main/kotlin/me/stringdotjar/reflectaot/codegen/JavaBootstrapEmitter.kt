package me.stringdotjar.reflectaot.codegen

import java.io.File

/** Java-source twin of [BootstrapBytecodeEmitter] when using [ReflectAOTOutput.JAVA] without bytecode bootstrap. */
object JavaBootstrapEmitter {

  /** Writes `ReflectAOTBootstrap.java` beside other generated sources. */
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
              ReflectAOTServices.installMethodIds(new ReflectAOTMethodIdTable());
          }

          private ReflectAOTBootstrap() {
          }
      }
      """.trimIndent() + "\n",
    )
  }
}
