package me.stringdotjar.reflectaot;

import java.util.List;

/**
 * Build-generated dispatch for {@link Reflect}. No {@link java.lang.reflect} types.
 *
 * <p>Installed via {@link ReflectAOTServices#install(ReflectAOTRuntime)} before first use.
 */
public interface ReflectAOTRuntime {

  /**
   * @param o receiver object
   * @param name member name
   * @return {@code true} when the receiver exposes the member under the same membership rules as
   *     {@link Reflect#fields(Object)}
   */
  boolean hasField(Object o, String name);

  /**
   * @param o receiver object
   * @param name field name (raw field semantics, not JavaBeans)
   * @return field value boxed as needed
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time
   * @throws IllegalArgumentException when the name is unknown for the receiver
   */
  Object field(Object o, String name);

  /**
   * @param o receiver object
   * @param name field name
   * @param value new value (must be compatible with the field type after widening/unboxing rules)
   */
  void setField(Object o, String name, Object value);

  /**
   * @param o receiver object
   * @param name property name (JavaBeans first, then raw field)
   * @return property value boxed as needed
   */
  Object getProperty(Object o, String name);

  /**
   * @param o receiver object
   * @param name property name
   * @param value new value
   */
  void setProperty(Object o, String name, Object value);

  /**
   * @param o receiver object
   * @param methodId build-time method identifier
   * @param args invocation arguments
   * @return invocation result, or {@code null} for void methods when specialized
   */
  Object callMethod(Object o, int methodId, List<?> args);

  /**
   * @param o receiver object
   * @return declared instance field and property names for the receiver type, including superclasses
   *     where applicable (never {@code null}; may be zero-length)
   */
  String[] fields(Object o);

  /**
   * @param o receiver object
   * @return shallow copy when specialized; otherwise throws
   */
  Object copy(Object o);

  /**
   * @param a first value
   * @param b second value
   * @return negative, zero, or positive ordering
   * @throws IllegalArgumentException when comparison is not supported for the concrete types
   */
  int compare(Object a, Object b);

  /**
   * @param methodIdA first build-time method identifier
   * @param methodIdB second build-time method identifier
   * @return {@code true} when the identifiers denote the same method target
   */
  boolean compareMethods(int methodIdA, int methodIdB);

  /**
   * @param v value to test
   * @return {@code true} for supported functional shapes (see project documentation)
   */
  boolean isFunction(Object v);

  /**
   * @param v value to test
   * @return {@code true} for non-primitive-like values per runtime classification rules
   */
  boolean isObject(Object v);

  /**
   * @param v value to test
   * @return {@code true} for Java {@code enum} constants
   */
  boolean isEnumValue(Object v);
}
