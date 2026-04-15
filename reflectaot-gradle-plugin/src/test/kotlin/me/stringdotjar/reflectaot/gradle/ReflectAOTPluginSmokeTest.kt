package me.stringdotjar.reflectaot.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

class ReflectAOTPluginSmokeTest {

  @TempDir lateinit var temp: File

  @Test
  fun `plugin generates and project compiles`() {
    File(temp, "settings.gradle").writeText("rootProject.name = 'reflectaot-smoke'\n")
    File(temp, "build.gradle").writeText(
      """
      plugins {
          id 'java'
          id 'me.stringdotjar.reflectaot'
      }
      repositories { mavenCentral() }
      """.trimIndent(),
    )

    val pkg = File(temp, "src/main/java/demo")
    pkg.mkdirs()
    File(pkg, "UseReflect.java").writeText(
      """
      package demo;
      import me.stringdotjar.reflectaot.Reflect;
      public class UseReflect {

          public static int cmp(Integer a, Integer b) { return Reflect.compare(a, b); }
      }
      """.trimIndent(),
    )

    val result =
      GradleRunner
        .create()
        .withProjectDir(temp)
        .withPluginClasspath()
        .withArguments("build")
        .forwardOutput()
        .build()

    assertTrue(result.output.contains("BUILD SUCCESSFUL"))
  }
}
