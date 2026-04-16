package me.stringdotjar.reflectaot;

import java.util.Objects;

/**
 * Opaque build-resolved identifier for {@link Reflect#callMethod(Object, ReflectMethodId,
 * java.util.List)}.
 *
 * <p>Created by the generated {@code ReflectAOTMethodIdTable} (installed from {@code
 * ReflectAOTBootstrap}). Do not construct manually; use {@link Reflect#methodId(Class, String,
 * String)} so the Gradle task can validate the target at build time.
 */
public final class ReflectMethodId {

  private final int id;

  /**
   * @param id compact token assigned by the generated {@code ReflectAOTMethodIdTable}
   */
  public ReflectMethodId(int id) {
    this.id = id;
  }

  /**
   * @return compact integer token used by the generated {@link ReflectAOTRuntime}
   */
  public int id() {
    return id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ReflectMethodId)) {
      return false;
    }
    return id == ((ReflectMethodId) o).id;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return "ReflectMethodId(" + id + ")";
  }
}
