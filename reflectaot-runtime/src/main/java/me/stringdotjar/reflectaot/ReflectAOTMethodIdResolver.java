package me.stringdotjar.reflectaot;

/**
 * Build-generated resolver for {@link Reflect#method(Class, String, String)} and {@link
 * Reflect#method(Class, String)}.
 */
public interface ReflectAOTMethodIdResolver {

  /**
   * @param clazz literal class passed to {@link Reflect#method(Class, String, String)}
   * @param name JVM method name
   * @param descriptor JVM method descriptor, for example {@code (F)V}
   * @return stable id for {@link Reflect#callMethod(Object, ReflectMethodId, java.util.List)}
   */
  ReflectMethodId resolve(Class<?> clazz, String name, String descriptor);

  /**
   * Resolves by class and method name only when the build proved a single public instance overload
   * for that pair; otherwise the generated implementation throws with an actionable message.
   *
   * @param clazz literal class from {@link Reflect#method(Class, String)}
   * @param name JVM method name
   * @return stable id for {@link Reflect#callMethod(Object, ReflectMethodId, java.util.List)}
   */
  ReflectMethodId resolve(Class<?> clazz, String name);
}
