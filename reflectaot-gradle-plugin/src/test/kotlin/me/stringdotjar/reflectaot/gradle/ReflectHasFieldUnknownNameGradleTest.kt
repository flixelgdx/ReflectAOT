package me.stringdotjar.reflectaot.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

/** `hasField` must not fail codegen for names that are absent at runtime (returns false). */
class ReflectHasFieldUnknownNameGradleTest {

  @TempDir lateinit var temp: File

  @Test
  fun `classes succeeds when hasField uses a name that is not on the receiver type`() {
    File(temp, "settings.gradle").writeText("rootProject.name = 'reflectaot-hasfield-unknown'\n")
    File(temp, "build.gradle").writeText(
      """
      plugins {
          id 'java'
          id 'me.stringdotjar.reflectaot'
      }
      repositories {
          mavenCentral()
      }
      dependencies {
          implementation files('${ReflectAOTTestRuntimeClasspath.runtimeJarPathForGroovyFiles()}')
      }
      """.trimIndent(),
    )

    val pkg = File(temp, "src/main/java/demo")
    pkg.mkdirs()
    File(pkg, "Holder.java").writeText(
      """
      package demo;
      public class Holder {
          public int someField;
      }
      """.trimIndent(),
    )
    File(pkg, "UseReflect.java").writeText(
      """
      package demo;
      import me.stringdotjar.reflectaot.Reflect;
      public class UseReflect {
          public static boolean check(Holder h) {
              return Reflect.hasField(h, "notARealMember");
          }
      }
      """.trimIndent(),
    )

    val result =
      GradleRunner
        .create()
        .withProjectDir(temp)
        .withPluginClasspath()
        .withArguments("classes")
        .forwardOutput()
        .build()

    assertTrue(result.output.contains("BUILD SUCCESSFUL"))
  }
}
