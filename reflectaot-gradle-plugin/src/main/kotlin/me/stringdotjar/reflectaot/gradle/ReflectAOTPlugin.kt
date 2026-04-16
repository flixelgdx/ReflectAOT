package me.stringdotjar.reflectaot.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import java.io.File

class ReflectAOTPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    project.plugins.apply(JavaPlugin::class.java)

    val ext = project.extensions.create("reflectaot", ReflectAOTExtension::class.java, project)

    val embeddedRuntime = ReflectAOTPlugin::class.java.getResource("/META-INF/reflectaot/reflectaot-runtime-embedded.jar")
    if (embeddedRuntime != null) {
      val version =
        ReflectAOTPlugin::class.java
          .getResourceAsStream("/META-INF/reflectaot/runtime-version.txt")
          ?.use { it.bufferedReader().readText().trim() }
          ?: "0.1.0-SNAPSHOT"
      val repoRoot =
        project.layout.buildDirectory.dir("reflectaot-embedded-m2/repository").get().asFile.apply {
          mkdirs()
        }
      val moduleDir =
        File(repoRoot, "me/stringdotjar/reflectaot-runtime-embedded/$version").apply {
          mkdirs()
        }
      val pom =
        """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>me.stringdotjar</groupId>
  <artifactId>reflectaot-runtime-embedded</artifactId>
  <version>$version</version>
  <packaging>jar</packaging>
</project>
"""
      File(moduleDir, "reflectaot-runtime-embedded-$version.pom").writeText(pom)
      embeddedRuntime.openStream().use { input ->
        File(moduleDir, "reflectaot-runtime-embedded-$version.jar").outputStream().use { out ->
          input.copyTo(out)
        }
      }
      ReflectAOTPlugin::class.java
        .getResourceAsStream("/META-INF/reflectaot/reflectaot-runtime-embedded-sources.jar")
        ?.use { input ->
          File(moduleDir, "reflectaot-runtime-embedded-$version-sources.jar").outputStream().use { out ->
            input.copyTo(out)
          }
        }
      project.repositories.maven { repo ->
        repo.url = project.uri(repoRoot)
        repo.name = "ReflectAOTEmbeddedRuntime"
      }
      project.dependencies.add(
        JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
        "me.stringdotjar:reflectaot-runtime-embedded:$version",
      )
    } else {
      val runtimeVersion =
        ReflectAOTPlugin::class.java
          .getResourceAsStream("/META-INF/reflectaot/runtime-version.txt")
          ?.use { it.bufferedReader().readText().trim() }
          ?: "0.1.0-SNAPSHOT"
      project.dependencies.add(
        JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
        "me.stringdotjar:reflectaot-runtime:$runtimeVersion",
      )
    }

    val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
    val main = javaExt.sourceSets.getByName("main")

    val genTask =
      project.tasks.register("generateReflectAOT", ReflectAOTDefaultTask::class.java) { task ->
        task.group = "reflectaot"
        task.description = "Scans bytecode and generates ReflectAOT registry and accessor artifacts."
        task.outputMode.set(ext.output)
        task.bytecodeOutputDirectory.set(ext.bytecodeOutputDirectory)
        task.javaOutputDirectory.set(ext.javaOutputDirectory)
        task.compiledClassesDirs.from(main.output.classesDirs)
        task.compileClasspath.from(main.compileClasspath)
        task.excludePackages.set(ext.excludePackages)
        task.extraClasses.set(ext.extraClasses)
      }

    genTask.configure { task ->
      task.dependsOn(project.tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME))
    }
    project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
      genTask.configure { task ->
        task.dependsOn(project.tasks.named("compileKotlin"))
      }
    }

    main.java.srcDir(ext.javaOutputDirectory)

    project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java).configure { jar ->
      jar.from(ext.bytecodeOutputDirectory)
      jar.dependsOn(genTask)
    }

    project.tasks.withType(Test::class.java).configureEach { test ->
      test.dependsOn(genTask)
      test.classpath = test.classpath.plus(project.files(ext.bytecodeOutputDirectory))
    }

    project.tasks.named(JavaPlugin.CLASSES_TASK_NAME).configure { classes ->
      classes.dependsOn(genTask)
    }
  }
}
