package me.stringdotjar.reflectaot.gradle

import me.stringdotjar.reflectaot.codegen.ReflectAOTCodegen
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ReflectAOTDefaultTask : DefaultTask() {

  @get:Input
  abstract val outputMode: Property<ReflectAOTOutput>

  @get:OutputDirectory
  abstract val bytecodeOutputDirectory: DirectoryProperty

  @get:OutputDirectory
  abstract val javaOutputDirectory: DirectoryProperty

  @get:Classpath
  abstract val compileClasspath: ConfigurableFileCollection

  @get:InputFiles
  abstract val compiledClassesDirs: ConfigurableFileCollection

  @get:Input
  abstract val excludePackages: ListProperty<String>

  @get:Input
  abstract val extraClasses: ListProperty<String>

  @TaskAction
  fun generate() {
    val mode = outputMode.get()
    val roots = LinkedHashSet<java.io.File>()
    compiledClassesDirs.files.forEach { roots.add(it) }
    compileClasspath.files.forEach { roots.add(it) }
    ReflectAOTCodegen.run(
      mode.toCodegen(),
      bytecodeOutputDirectory.get().asFile,
      javaOutputDirectory.get().asFile,
      roots,
      excludePackages.get(),
      extraClasses.get(),
    )
    logger.lifecycle("ReflectAOT: generated output mode=$mode")
  }
}
