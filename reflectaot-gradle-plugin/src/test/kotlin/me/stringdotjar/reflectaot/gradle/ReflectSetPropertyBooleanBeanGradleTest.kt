package me.stringdotjar.reflectaot.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

class ReflectSetPropertyBooleanBeanGradleTest {

  @TempDir lateinit var temp: File

  @Test
  fun `classes succeeds with setProperty on boolean bean with private backing field`() {
    File(temp, "settings.gradle").writeText("rootProject.name = 'reflectaot-setprop-bean'\n")
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

    val pkg = File(temp, "src/main/java/me/stringdotjar/yesnt")
    pkg.mkdirs()
    File(pkg, "Player.java").writeText(
      """
      package me.stringdotjar.yesnt;
      public class Player {
          public String name;
          public int health;
          public final int ID = 67;
          private boolean alive = true;
          public Player(String name, int health) {
              this.name = name;
              this.health = health;
          }
          public void doSmth() {
              System.out.println("yay");
          }
          public boolean isAlive() {
              return alive;
          }
          public void setAlive(boolean alive) {
              this.alive = alive;
          }
      }
      """.trimIndent(),
    )
    File(pkg, "Main.java").writeText(
      """
      package me.stringdotjar.yesnt;
      import me.stringdotjar.reflectaot.Reflect;
      public class Main {
          public static void main(String[] args) {
              Player player = new Player("stringdotjar", 100);
              Reflect.setProperty(player, "alive", false);
              System.out.println(Reflect.property(player, "alive"));
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
