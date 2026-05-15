package me.stringdotjar.reflectaot.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test

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
      // dependencyResolutionManagement (FAIL_ON_PROJECT_REPOS / PREFER_SETTINGS) ignores
      // project.repositories — so a file repo under the build directory is never consulted.
      // Installing into ~/.m2 matches mavenLocal() in settings.gradle, which most multi-module
      // builds already use for snapshots; IntelliJ can attach -sources.jar from the same tree.
      EmbeddedRuntimeMavenLocal.install(
        version,
        embeddedRuntime.openStream(),
        ReflectAOTPlugin::class.java.getResourceAsStream("/META-INF/reflectaot/reflectaot-runtime-embedded-sources.jar"),
      )
      project.logger.lifecycle(
        "ReflectAOT: installed me.stringdotjar:reflectaot-runtime-embedded:$version into ~/.m2/repository " +
          "(for resolution when settings use mavenLocal()).",
      )
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

    val genClasses = project.files(ext.bytecodeOutputDirectory).builtBy(genTask)
    project.dependencies.add(JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME, genClasses)

    project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java).configure { jar ->
      jar.from(ext.bytecodeOutputDirectory)
      jar.dependsOn(genTask)
    }

    project.tasks.withType(Test::class.java).configureEach { test ->
      test.dependsOn(genTask)
    }

    project.tasks.named(JavaPlugin.CLASSES_TASK_NAME).configure { classes ->
      classes.dependsOn(genTask)
    }
  }
}
