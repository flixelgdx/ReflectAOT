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
