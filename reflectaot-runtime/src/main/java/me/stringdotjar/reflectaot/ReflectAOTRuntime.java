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
   * @param func opaque call-site token (never a {@link java.lang.reflect.Method})
   * @param args invocation arguments
   * @return invocation result
   */
  Object callMethod(Object o, Object func, List<?> args);

  /**
   * @param o receiver object
   * @return declared instance field names for the receiver type, including superclasses where
   *     applicable
   */
  List<String> fields(Object o);

  /**
   * @param o receiver object
   * @return shallow copy when specialized; otherwise throws
   */
  Object copy(Object o);

  /**
   * @param o receiver object
   * @param name field name
   * @return {@code true} when delete succeeds (Haxe anonymous structures); JVM default is {@code
   *     false}
   */
  boolean deleteField(Object o, String name);

  /**
   * @param a first value
   * @param b second value
   * @return negative, zero, or positive ordering
   * @throws IllegalArgumentException when comparison is not supported for the concrete types
   */
  int compare(Object a, Object b);

  /**
   * @param f1 first token
   * @param f2 second token
   * @return {@code true} when the two tokens are considered equal by the Haxe-style rules
   *     implemented for JVM
   */
  boolean compareMethods(Object f1, Object f2);

  /**
   * @param v value to test
   * @return {@code true} for supported functional shapes (see README)
   */
  boolean isFunction(Object v);

  /**
   * @param v value to test
   * @return {@code true} for non-primitive-like values (Haxe-style classification)
   */
  boolean isObject(Object v);

  /**
   * @param f opaque token
   * @return varargs wrapper when specialized
   */
  Object makeVarArgs(Object f);

  /**
   * @param v value to test
   * @return {@code true} for Java {@code enum} constants
   */
  boolean isEnumValue(Object v);
}
