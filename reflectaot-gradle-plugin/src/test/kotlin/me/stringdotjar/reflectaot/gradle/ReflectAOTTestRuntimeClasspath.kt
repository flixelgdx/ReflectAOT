package me.stringdotjar.reflectaot.gradle

/**
 * TestKit projects compile against `reflectaot-runtime` via a `files(...)` dependency because the plugin no longer
 * adds that dependency automatically.
 */
object ReflectAOTTestRuntimeClasspath {

  /** Absolute path to `reflectaot-runtime` jar, forward slashes for Groovy `files('...')`. */
  fun runtimeJarPathForGroovyFiles(): String =
    (System.getProperty("reflectaot.runtime.jar") ?: error("reflectaot.runtime.jar system property not set (set in reflectaot-gradle-plugin build)"))
      .replace('\\', '/')
}
