package me.stringdotjar.reflectaot;

/**
 * Holds the active {@link ReflectAOTRuntime} implementation (normally generated and installed at
 * class init).
 */
public final class ReflectAOTServices {

  private static volatile ReflectAOTRuntime runtime = UnconfiguredReflectAOTRuntime.INSTANCE;

  private static volatile ReflectAOTMethodIdResolver methodIds = null;

  private ReflectAOTServices() {}

  /**
   * Replaces the global runtime (used by generated bootstrap). Not synchronized; install before
   * concurrent use.
   *
   * @param runtime non-null runtime implementation
   * @throws IllegalArgumentException when {@code runtime} is {@code null}
   */
  public static void install(ReflectAOTRuntime runtime) {
    if (runtime == null) {
      throw new IllegalArgumentException("runtime");
    }
    ReflectAOTServices.runtime = runtime;
  }

  /**
   * @return the currently installed {@link ReflectAOTRuntime} (defaults to {@link
   *     UnconfiguredReflectAOTRuntime} until {@link #install(ReflectAOTRuntime)} runs)
   */
  public static ReflectAOTRuntime runtime() {
    return runtime;
  }

  /**
   * Installs the generated {@link ReflectAOTMethodIdResolver} (normally from {@code
   * ReflectAOTBootstrap}).
   *
   * @param resolver non-null resolver
   */
  public static void installMethodIds(ReflectAOTMethodIdResolver resolver) {
    if (resolver == null) {
      throw new IllegalArgumentException("resolver");
    }
    ReflectAOTServices.methodIds = resolver;
  }

  /**
   * @return resolver installed by generated bootstrap, or {@code null} before bootstrap runs
   */
  public static ReflectAOTMethodIdResolver methodIdsOrNull() {
    return methodIds;
  }

  /**
   * Resolves a {@link ReflectMethodId} for {@link Reflect#methodId(Class, String, String)}.
   *
   * @param clazz receiver class literal from the call site
   * @param name JVM method name
   * @param descriptor JVM method descriptor
   * @return build-stable id token
   * @throws UnsupportedOperationException when no resolver is installed yet
   */
  public static ReflectMethodId resolveMethodId(Class<?> clazz, String name, String descriptor) {
    ReflectAOTMethodIdResolver r = methodIds;
    if (r == null) {
      throw new UnsupportedOperationException(
          "Reflect.methodId: no generated ReflectAOTMethodIdTable installed. "
              + "Run the generateReflectAOT Gradle task and ensure ReflectAOTBootstrap is on the classpath.");
    }
    return r.resolve(clazz, name, descriptor);
  }

  /**
   * Resolves a {@link ReflectMethodId} for {@link Reflect#methodId(Class, String)}.
   *
   * @param clazz receiver class literal from the call site
   * @param name JVM method name
   * @return build-stable id token
   * @throws UnsupportedOperationException when no resolver is installed yet
   */
  public static ReflectMethodId resolveMethodId(Class<?> clazz, String name) {
    ReflectAOTMethodIdResolver r = methodIds;
    if (r == null) {
      throw new UnsupportedOperationException(
          "Reflect.methodId: no generated ReflectAOTMethodIdTable installed. "
              + "Run the generateReflectAOT Gradle task and ensure ReflectAOTBootstrap is on the classpath.");
    }
    return r.resolve(clazz, name);
  }
}
