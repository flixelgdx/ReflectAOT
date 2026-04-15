package me.stringdotjar.reflectaot;

import java.util.List;

enum UnconfiguredReflectAOTRuntime implements ReflectAOTRuntime {
  INSTANCE;

  private static UnsupportedOperationException nx() {
    return new UnsupportedOperationException(
        "ReflectAOT is not configured: no generated ReflectAOTRegistry was installed. "
            + "Apply the ReflectAOT Gradle plugin and run the generate task.");
  }

  @Override
  public boolean hasField(Object o, String name) {
    throw nx();
  }

  @Override
  public Object field(Object o, String name) {
    throw nx();
  }

  @Override
  public void setField(Object o, String name, Object value) {
    throw nx();
  }

  @Override
  public Object getProperty(Object o, String name) {
    throw nx();
  }

  @Override
  public void setProperty(Object o, String name, Object value) {
    throw nx();
  }

  @Override
  public Object callMethod(Object o, Object func, List<?> args) {
    throw nx();
  }

  @Override
  public List<String> fields(Object o) {
    throw nx();
  }

  @Override
  public Object copy(Object o) {
    throw nx();
  }

  @Override
  public boolean deleteField(Object o, String name) {
    throw nx();
  }

  @Override
  public int compare(Object a, Object b) {
    throw nx();
  }

  @Override
  public boolean compareMethods(Object f1, Object f2) {
    throw nx();
  }

  @Override
  public boolean isFunction(Object v) {
    throw nx();
  }

  @Override
  public boolean isObject(Object v) {
    throw nx();
  }

  @Override
  public Object makeVarArgs(Object f) {
    throw nx();
  }

  @Override
  public boolean isEnumValue(Object v) {
    throw nx();
  }
}
