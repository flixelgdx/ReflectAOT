package me.stringdotjar.reflectaot.gradle

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

open class ReflectAOTExtension(project: Project) {

  /** What to emit: bytecode, Java sources, or both. Defaults to [ReflectAOTOutput.CLASS]. */
  val output: Property<ReflectAOTOutput> =
    project.objects.property(ReflectAOTOutput::class.java).convention(ReflectAOTOutput.CLASS)

  /** Root directory for generated `.class` files (ASM). */
  val bytecodeOutputDirectory: DirectoryProperty =
    project.objects.directoryProperty().convention(
      project.layout.buildDirectory.dir("generated/reflectaot/classes"),
    )

  /** Root directory for generated `.java` files. */
  val javaOutputDirectory: DirectoryProperty =
    project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("generated/reflectaot/java"))

  /** Dotted package prefixes excluded from bytecode scanning (in addition to built-in JDK/Gradle excludes). */
  val excludePackages: ListProperty<String> =
    project.objects.listProperty(String::class.java).convention(emptyList())

  /** Fully qualified class names to always generate accessors for (even if not discovered by scanning). */
  val extraClasses: ListProperty<String> =
    project.objects.listProperty(String::class.java).convention(emptyList())
}
