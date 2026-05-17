package me.stringdotjar.reflectaot.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

class ReflectFieldVariableMemberNameTest {

  @TempDir lateinit var temp: File

  @Test
  fun `classes succeeds with Reflect field and string local member name`() {
    File(temp, "settings.gradle").writeText("rootProject.name = 'reflectaot-field-local'\n")
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
          public static Object read(Holder h) {
              String fieldName = "someField";
              return Reflect.field(h, fieldName);
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
