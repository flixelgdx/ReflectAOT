package me.stringdotjar.reflectaot;

import java.util.List;

/**
 * Placeholder {@link ReflectAOTRuntime} installed by the initial generated bootstrap until
 * specialized accessors are emitted. Safe for early integration; replace with generated dispatch.
 */
public final class ReflectAOTStubRuntime implements ReflectAOTRuntime {

  @Override
  public boolean hasField(Object o, String name) {
    return false;
  }

  @Override
  public Object field(Object o, String name) {
    throw new UnsupportedOperationException("field not specialized for " + safe(o) + " / " + name);
  }

  @Override
  public void setField(Object o, String name, Object value) {
    throw new UnsupportedOperationException("setField not specialized");
  }

  @Override
  public Object getProperty(Object o, String name) {
    throw new UnsupportedOperationException("getProperty not specialized");
  }

  @Override
  public void setProperty(Object o, String name, Object value) {
    throw new UnsupportedOperationException("setProperty not specialized");
  }

  @Override
  public Object callMethod(Object o, int methodId, List<?> args) {
    throw new UnsupportedOperationException("callMethod not specialized");
  }

  @Override
  public String[] fields(Object o) {
    return ReflectAOTDefaultDispatch.emptyStringArray();
  }

  @Override
  public Object copy(Object o) {
    throw new UnsupportedOperationException("copy not specialized");
  }

  @Override
  public int compare(Object a, Object b) {
    if (a == b) {
      return 0;
    }
    if (a == null) {
      return -1;
    }
    if (b == null) {
      return 1;
    }
    if (a instanceof Comparable<?> && a.getClass().isAssignableFrom(b.getClass())) {
      @SuppressWarnings("unchecked")
      Comparable<Object> ca = (Comparable<Object>) a;
      return ca.compareTo(b);
    }
    if (a instanceof Number && b instanceof Number) {
      return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
    }
    throw new IllegalArgumentException(
        "Reflect.compare: unsupported types " + a.getClass() + " vs " + b.getClass());
  }

  @Override
  public boolean compareMethods(int methodIdA, int methodIdB) {
    return methodIdA == methodIdB;
  }

  @Override
  public boolean isFunction(Object v) {
    return v instanceof Runnable || v instanceof java.util.concurrent.Callable<?>;
  }

  @Override
  public boolean isObject(Object v) {
    return v != null
        && !(v instanceof Boolean
            || v instanceof Number
            || v instanceof Character
            || v instanceof String);
  }

  @Override
  public boolean isEnumValue(Object v) {
    return v != null && v.getClass().isEnum();
  }

  private static String safe(Object o) {
    return o == null ? "null" : o.getClass().getName();
  }
}
