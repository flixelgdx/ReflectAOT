package me.stringdotjar.reflectaot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM-facing mirror of the Haxe standard library {@code Reflect} API.
 *
 * <p>Behavior and naming follow <a href="https://api.haxe.org/Reflect.html">Haxe Reflect</a> where
 * practical. {@code Dynamic} maps to {@link Object}; {@code Array<Dynamic>} maps to {@link List} or
 * {@code Object[]} at call sites - this API uses {@link List} for variadic call arguments for Java
 * ergonomics.
 *
 * <p><b>No {@link java.lang.reflect}</b>: this class and generated accessors must never use the JDK
 * reflection package. Dispatch is implemented by {@link ReflectAOTRuntime} (generated).
 *
 * <p><b>JavaBeans:</b> {@link #getProperty(Object, String)} / {@link #setProperty(Object, String,
 * Object)} prefer JavaBean accessors ({@code getX}/{@code isX}, {@code setX}) before raw fields
 * where that matches Haxe semantics for properties.
 */
public final class Reflect {

  static {
    try {
      Class.forName("me.stringdotjar.reflectaot.generated.ReflectAOTBootstrap");
    } catch (ClassNotFoundException ignored) {
      // Generated bootstrap not on classpath yet (e.g. before generateReflectAOT runs).
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  private Reflect() {}

  /**
   * Haxe: {@code compare(a:Dynamic, b:Dynamic):Int}
   *
   * <p>Ordering is defined for {@code null}, numeric primitives, and {@link String}. Other types
   * follow the rules implemented by the installed {@link ReflectAOTRuntime}.
   *
   * @param a first value
   * @param b second value
   * @return negative, zero, or positive ordering
   * @throws IllegalArgumentException when comparison is not supported for the concrete types
   */
  public static int compare(Object a, Object b) {
    return ReflectAOTServices.runtime().compare(a, b);
  }

  /**
   * Haxe: {@code compareMethods(f1:Dynamic, f2:Dynamic):Bool}
   *
   * @param f1 first token
   * @param f2 second token
   * @return {@code true} when the two tokens are equal under the JVM-facing rules (see README)
   */
  public static boolean compareMethods(Object f1, Object f2) {
    return ReflectAOTServices.runtime().compareMethods(f1, f2);
  }

  /**
   * Haxe: {@code copy(o:T):T} - shallow structure copy intent; see README for Java beans.
   *
   * @param <T> static receiver type at the call site
   * @param o object to copy
   * @return shallow copy when specialized
   * @throws UnsupportedOperationException when copy is not specialized for the receiver type
   */
  @SuppressWarnings("unchecked")
  public static <T> T copy(T o) {
    return (T) ReflectAOTServices.runtime().copy(o);
  }

  /**
   * Haxe: {@code deleteField(o:Dynamic, field:String):Bool} - anonymous structures on Haxe; JVM
   * semantics documented in README.
   *
   * @param o receiver object
   * @param field field name
   * @return {@code true} when delete succeeds; default JVM behavior is {@code false}
   */
  public static boolean deleteField(Object o, String field) {
    return ReflectAOTServices.runtime().deleteField(o, field);
  }

  /**
   * Haxe: {@code field(o:Dynamic, field:String):Dynamic} - raw field read (not JavaBean).
   *
   * @param o receiver object
   * @param field field name
   * @return field value boxed as needed
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time
   * @throws IllegalArgumentException when the name is unknown for the receiver
   */
  public static Object field(Object o, String field) {
    return ReflectAOTServices.runtime().field(o, field);
  }

  /**
   * Haxe: {@code fields(o:Dynamic):Array<String>}
   *
   * @param o receiver object
   * @return declared instance field names (never {@code null}; may be empty)
   */
  public static List<String> fields(Object o) {
    List<String> r = ReflectAOTServices.runtime().fields(o);
    return r == null ? Collections.<String>emptyList() : r;
  }

  /**
   * Haxe: {@code callMethod(o:Dynamic, func:Dynamic, args:Array<Dynamic>):Dynamic}
   *
   * @param o receiver object
   * @param func opaque call-site token (never a {@link java.lang.reflect.Method})
   * @param args invocation arguments (may be empty, never {@code null} after normalization)
   * @return invocation result
   * @throws UnsupportedOperationException when call sites were not specialized at build time
   */
  public static Object callMethod(Object o, Object func, List<?> args) {
    List<?> safe = args == null ? Collections.emptyList() : args;
    return ReflectAOTServices.runtime().callMethod(o, func, safe);
  }

  /**
   * Same as {@link #callMethod(Object, Object, List)} using a varargs array.
   *
   * @param o receiver object
   * @param func opaque call-site token (never a {@link java.lang.reflect.Method})
   * @param args invocation arguments (may be {@code null} or empty)
   * @return invocation result
   */
  public static Object callMethod(Object o, Object func, Object... args) {
    if (args == null || args.length == 0) {
      return callMethod(o, func, Collections.emptyList());
    }
    List<Object> list = new ArrayList<Object>(args.length);
    Collections.addAll(list, args);
    return callMethod(o, func, list);
  }

  /**
   * Haxe: {@code getProperty(o:Dynamic, field:String):Dynamic} - JavaBeans first, then field.
   *
   * @param o receiver object
   * @param field property name
   * @return property value boxed as needed
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time
   * @throws IllegalArgumentException when the name is unknown for the receiver
   */
  public static Object getProperty(Object o, String field) {
    return ReflectAOTServices.runtime().getProperty(o, field);
  }

  /**
   * Haxe: {@code hasField(o:Dynamic, field:String):Bool}
   *
   * @param o receiver object
   * @param field member name
   * @return {@code true} when the member exists under the same membership rules as {@link
   *     #fields(Object)}
   */
  public static boolean hasField(Object o, String field) {
    return ReflectAOTServices.runtime().hasField(o, field);
  }

  /**
   * Haxe: {@code isEnumValue(v:Dynamic):Bool}
   *
   * @param v value to test
   * @return {@code true} for Java {@code enum} constants
   */
  public static boolean isEnumValue(Object v) {
    return ReflectAOTServices.runtime().isEnumValue(v);
  }

  /**
   * Haxe: {@code isFunction(v:Dynamic):Bool} - without {@code java.lang.reflect}.
   *
   * @param v value to test
   * @return {@code true} for supported functional shapes (see README)
   */
  public static boolean isFunction(Object v) {
    return ReflectAOTServices.runtime().isFunction(v);
  }

  /**
   * Haxe: {@code isObject(v:Dynamic):Bool}
   *
   * @param v value to test
   * @return {@code true} for non-primitive-like values (Haxe-style classification)
   */
  public static boolean isObject(Object v) {
    return ReflectAOTServices.runtime().isObject(v);
  }

  /**
   * Haxe: {@code makeVarArgs(f:Dynamic):Dynamic}
   *
   * @param f opaque token
   * @return varargs wrapper when specialized
   * @throws UnsupportedOperationException when not specialized
   */
  public static Object makeVarArgs(Object f) {
    return ReflectAOTServices.runtime().makeVarArgs(f);
  }

  /**
   * Haxe: {@code setField(o:Dynamic, field:String, value:Dynamic):Void} - raw field write.
   *
   * @param o receiver object
   * @param field field name
   * @param value new value
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time
   * @throws IllegalArgumentException when the name is unknown for the receiver
   */
  public static void setField(Object o, String field, Object value) {
    ReflectAOTServices.runtime().setField(o, field, value);
  }

  /**
   * Haxe: {@code setProperty(o:Dynamic, field:String, value:Dynamic):Void} - JavaBean setter
   * preferred, then field.
   *
   * @param o receiver object
   * @param field property name
   * @param value new value
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time
   * @throws IllegalArgumentException when the name is unknown for the receiver
   */
  public static void setProperty(Object o, String field, Object value) {
    ReflectAOTServices.runtime().setProperty(o, field, value);
  }
}
