package me.stringdotjar.reflectaot.gradle

import java.io.File
import java.io.InputStream

/**
 * Publishes the plugin-bundled runtime into the **standard Maven local** layout under
 * {@code ~/.m2/repository/...}.
 *
 * <p>This is required when the build uses {@code dependencyResolutionManagement} with
 * {@code FAIL_ON_PROJECT_REPOS} or {@code PREFER_SETTINGS}: Gradle will not use
 * {@code project.repositories} for that dependency, but it <em>will</em> use {@code mavenLocal()}
 * declared in {@code settings.gradle}. Installing here makes {@code
 * me.stringdotjar:reflectaot-runtime-embedded} resolvable the same way as any other {@code mvn
 * install} artifact (including IDE sources when {@code -sources.jar} is present).
 */
internal object EmbeddedRuntimeMavenLocal {

  fun install(
    version: String,
    mainJar: InputStream,
    sourcesJar: InputStream?,
  ) {
    try {
      val moduleDir =
        File(
          System.getProperty("user.home"),
          ".m2/repository/me/stringdotjar/reflectaot-runtime-embedded/$version",
        ).apply {
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
      File(moduleDir, "reflectaot-runtime-embedded-$version.jar").outputStream().use { out ->
        mainJar.copyTo(out)
      }
      if (sourcesJar != null) {
        File(moduleDir, "reflectaot-runtime-embedded-$version-sources.jar").outputStream().use { out ->
          sourcesJar.copyTo(out)
        }
      }
    } finally {
      mainJar.close()
      sourcesJar?.close()
    }
  }
}
