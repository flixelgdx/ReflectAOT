package me.stringdotjar.reflectaot;

/**
 * Build-generated resolver for {@link Reflect#methodId(Class, String, String)}.
 */
public interface ReflectAOTMethodIdResolver {

  /**
   * @param clazz literal class passed to {@link Reflect#methodId(Class, String, String)}
   * @param name JVM method name
   * @param descriptor JVM method descriptor, for example {@code (F)V}
   * @return stable id for {@link Reflect#callMethod(Object, ReflectMethodId, java.util.List)}
   */
  ReflectMethodId resolve(Class<?> clazz, String name, String descriptor);
}
