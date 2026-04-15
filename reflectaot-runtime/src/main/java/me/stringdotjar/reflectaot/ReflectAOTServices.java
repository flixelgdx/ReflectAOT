package me.stringdotjar.reflectaot;

/**
 * Holds the active {@link ReflectAOTRuntime} implementation (normally generated and installed at
 * class init).
 */
public final class ReflectAOTServices {

  private static volatile ReflectAOTRuntime runtime = UnconfiguredReflectAOTRuntime.INSTANCE;

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
}
