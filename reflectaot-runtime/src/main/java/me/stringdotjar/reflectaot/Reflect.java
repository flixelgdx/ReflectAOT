package me.stringdotjar.reflectaot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static entry points for build-time specialized reflection without {@link java.lang.reflect}.
 *
 * <p>This class defines the reflection operations exposed to gameplay and framework code. A
 * generated {@code me.stringdotjar.reflectaot.generated.ReflectAOTRegistry} supplies the
 * implementation for each build; implementations should preserve the same behavior contract so
 * code behaves consistently across JVM and AOT targets.
 *
 * <p>Implementations are expected to avoid repeated class scanning at runtime: reflective access is
 * resolved during compilation and emitted as direct field access and dispatch where possible.
 * Method dispatch uses compact integer identifiers assigned at build time (see {@link
 * #callMethod(Object, int, List)} and {@link #compareMethods(int, int)}).
 *
 * <p><b>No {@link java.lang.reflect}</b>: this class and generated accessors must never use the JDK
 * reflection package. Dispatch is implemented by a generated {@link ReflectAOTRuntime} subtype
 * ({@code ReflectAOTRegistry}).
 *
 * <p><b>JavaBeans:</b> {@link #getProperty(Object, String)} / {@link #setProperty(Object, String,
 * Object)} prefer JavaBean accessors ({@code getX}/{@code isX}, {@code setX}) before raw fields where
 * that matches common Java property conventions.
 */
public final class Reflect {

  static {
    try {
      Class.forName("me.stringdotjar.reflectaot.generated.ReflectAOTBootstrap");
    } catch (ClassNotFoundException ignored) {
      // Generated bootstrap not on classpath yet (e.g. before generateReflectAOT runs).
    } catch (Throwable t) {
      // Avoid ExceptionInInitializerError here: TeaVM and other JCL subsets often omit it, which
      // breaks transpilation or runtime. Use a normal unchecked exception instead.
      if (t instanceof Error) {
        throw (Error) t;
      }
      throw new IllegalStateException("ReflectAOT bootstrap class failed to load", t);
    }
  }

  private Reflect() {}

  /**
   * Compares two values for ordering.
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
   * Compares two build-time method identifiers for logical equality.
   *
   * <p>Identifiers are assigned when the build scans call sites; two identifiers are equal when
   * they denote the same resolved method target.
   *
   * @param methodIdA first method identifier
   * @param methodIdB second method identifier
   * @return {@code true} when both denote the same method target
   */
  public static boolean compareMethods(int methodIdA, int methodIdB) {
    return ReflectAOTServices.runtime().compareMethods(methodIdA, methodIdB);
  }

  /**
   * Same as {@link #compareMethods(int, int)} using {@link ReflectMethodId} tokens.
   *
   * @param methodIdA first method identifier
   * @param methodIdB second method identifier
   * @return {@code true} when both denote the same resolved method target
   */
  public static boolean compareMethods(ReflectMethodId methodIdA, ReflectMethodId methodIdB) {
    if (methodIdA == null || methodIdB == null) {
      throw new IllegalArgumentException("ReflectMethodId must not be null");
    }
    return compareMethods(methodIdA.id(), methodIdB.id());
  }

  /**
   * Creates a shallow copy of the source object.
   *
   * <p>Implementations typically require a no-argument constructor and then copy non-static field
   * values from source to destination.
   *
   * @param <T> static receiver type at the call site
   * @param o source instance
   * @return copied instance when specialized
   * @throws UnsupportedOperationException when copy is not specialized for the receiver type
   */
  @SuppressWarnings("unchecked")
  public static <T> T copy(T o) {
    return (T) ReflectAOTServices.runtime().copy(o);
  }

  /**
   * Reads a field value from the target instance.
   *
   * <p>Implementations should resolve inherited fields and may allow non-public access depending on
   * platform constraints. If the field cannot be resolved or read, an exception should be thrown.
   *
   * @param o receiver object
   * @param field field name to read
   * @return the current field value boxed as needed
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time
   * @throws IllegalArgumentException when the name is unknown for the receiver
   */
  public static Object field(Object o, String field) {
    return ReflectAOTServices.runtime().field(o, field);
  }

  /**
   * Returns the available field and property names for the target.
   *
   * <p>The result should include inherited members where applicable. Implementations may return a
   * freshly allocated array for each call; callers should not assume caching.
   *
   * @param o receiver object
   * @return field names available on the target type (never {@code null}; may be zero-length)
   */
  public static String[] fields(Object o) {
    String[] r = ReflectAOTServices.runtime().fields(o);
    return r == null ? ReflectAOTDefaultDispatch.emptyStringArray() : r;
  }

  /**
   * Invokes a method using a build-time method identifier and arguments.
   *
   * <p>Implementations should resolve overloads using the identifier assigned when the call site
   * was specialized. The identifier is an {@code int} to keep heap usage low and avoid allocating
   * token objects.
   *
   * @param o receiver object
   * @param methodId build-time method identifier
   * @param args invocation arguments (may be empty, never {@code null} after normalization)
   * @return the invocation result, or {@code null} for void methods when specialized
   * @throws UnsupportedOperationException when call sites were not specialized at build time
   */
  public static Object callMethod(Object o, int methodId, List<?> args) {
    List<?> safe = args == null ? Collections.emptyList() : args;
    return ReflectAOTServices.runtime().callMethod(o, methodId, safe);
  }

  /**
   * Same as {@link #callMethod(Object, int, List)} using a {@link ReflectMethodId} from {@link
   * #methodId(Class, String, String)} (no primitive {@code int} cast at call sites).
   *
   * @param o receiver object
   * @param methodId build-resolved method token
   * @param args invocation arguments (may be empty, never {@code null} after normalization)
   * @return invocation result, or {@code null} for void methods when specialized
   */
  public static Object callMethod(Object o, ReflectMethodId methodId, List<?> args) {
    if (methodId == null) {
      throw new IllegalArgumentException("methodId");
    }
    return callMethod(o, methodId.id(), args);
  }

  /**
   * Same as {@link #callMethod(Object, int, List)} using a varargs array.
   *
   * @param o receiver object
   * @param methodId build-time method identifier
   * @param args invocation arguments (may be {@code null} or empty)
   * @return the invocation result, or {@code null} for void methods when specialized
   */
  public static Object callMethod(Object o, int methodId, Object... args) {
    if (args == null || args.length == 0) {
      return callMethod(o, methodId, Collections.emptyList());
    }
    List<Object> list = new ArrayList<Object>(args.length);
    Collections.addAll(list, args);
    return callMethod(o, methodId, list);
  }

  /**
   * Same as {@link #callMethod(Object, int, Object...)} using {@link ReflectMethodId}.
   *
   * @param o receiver object
   * @param methodId build-resolved method token
   * @param args invocation arguments (may be {@code null} or empty)
   * @return invocation result, or {@code null} for void methods when specialized
   */
  public static Object callMethod(Object o, ReflectMethodId methodId, Object... args) {
    if (methodId == null) {
      throw new IllegalArgumentException("methodId");
    }
    return callMethod(o, methodId.id(), args);
  }

  /**
   * Returns a build-validated method token for {@link #callMethod(Object, ReflectMethodId,
   * Object...)}.
   *
   * <p>The {@code clazz}, {@code name}, and {@code descriptor} arguments must be <em>compile-time
   * constants</em> in user bytecode (string literals and {@code SomeType.class}) so the Gradle
   * generator can resolve the member and assign a stable id.
   *
   * @param clazz receiver class literal (for example {@code FlixelSprite.class})
   * @param name JVM method name (for example {@code "changeX"})
   * @param descriptor JVM method descriptor (for example {@code "(F)V"})
   * @return opaque method token for {@link #callMethod(Object, ReflectMethodId, Object...)}
   */
  public static ReflectMethodId methodId(Class<?> clazz, String name, String descriptor) {
    if (clazz == null) {
      throw new IllegalArgumentException("clazz");
    }
    if (name == null) {
      throw new IllegalArgumentException("name");
    }
    if (descriptor == null) {
      throw new IllegalArgumentException("descriptor");
    }
    return ReflectAOTServices.resolveMethodId(clazz, name, descriptor);
  }

  /**
   * Same as {@link #methodId(Class, String, String)} but the JVM method descriptor is inferred at
   * build time when exactly one public instance method with {@code name} exists on {@code clazz}
   * (including supertypes).
   *
   * <p>If several overloads share the same name, the Gradle task fails with a list of descriptors;
   * use {@link #methodId(Class, String, String)} for those cases.
   *
   * <p>{@code clazz} and {@code name} must be compile-time constants (typically {@code Foo.class}
   * and a string literal) so the scanner can validate the call.
   *
   * @param clazz receiver class literal
   * @param name JVM method name
   * @return opaque method token for {@link #callMethod(Object, ReflectMethodId, Object...)}
   */
  public static ReflectMethodId methodId(Class<?> clazz, String name) {
    if (clazz == null) {
      throw new IllegalArgumentException("clazz");
    }
    if (name == null) {
      throw new IllegalArgumentException("name");
    }
    return ReflectAOTServices.resolveMethodId(clazz, name);
  }

  /**
   * Reads a property value from the target.
   *
   * <p>Implementations may resolve JavaBean getters when available and fall back to direct field
   * access when appropriate.
   *
   * @param o receiver object
   * @param field property name to resolve
   * @return the resolved property value boxed as needed
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time
   * @throws IllegalArgumentException when the name is unknown for the receiver
   */
  public static Object getProperty(Object o, String field) {
    return ReflectAOTServices.runtime().getProperty(o, field);
  }

  /**
   * Checks whether a field with the given name exists on the target instance.
   *
   * <p>Implementations should include inherited fields from superclasses. Returning {@code false}
   * means the field cannot be resolved for read or write operations.
   *
   * @param o receiver object
   * @param field field name to search for
   * @return {@code true} if the field exists on the target type, otherwise {@code false}
   */
  public static boolean hasField(Object o, String field) {
    return ReflectAOTServices.runtime().hasField(o, field);
  }

  /**
   * Returns whether the value is a Java {@code enum} constant.
   *
   * @param v value to test
   * @return {@code true} for Java {@code enum} constants
   */
  public static boolean isEnumValue(Object v) {
    return ReflectAOTServices.runtime().isEnumValue(v);
  }

  /**
   * Returns whether a value should be treated as a callable without using {@link
   * java.lang.reflect}.
   *
   * @param v value to test
   * @return {@code true} for supported functional shapes (see project documentation)
   */
  public static boolean isFunction(Object v) {
    return ReflectAOTServices.runtime().isFunction(v);
  }

  /**
   * Returns whether a value should be treated as an object value in reflection contexts.
   *
   * <p>This is useful for parity with dynamic reflection APIs and for differentiating scalar values
   * from richer object structures.
   *
   * @param v value to test
   * @return {@code true} if the value is treated as an object value
   */
  public static boolean isObject(Object v) {
    return ReflectAOTServices.runtime().isObject(v);
  }

  /**
   * Writes a field value to the target instance.
   *
   * <p>If a field is immutable, final, unsupported, or cannot be written safely, implementations
   * should throw an explicit exception instead of silently failing.
   *
   * @param o receiver object
   * @param field field name to write
   * @param value new value to store
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time
   * @throws IllegalArgumentException when the name is unknown for the receiver
   */
  public static void setField(Object o, String field, Object value) {
    ReflectAOTServices.runtime().setField(o, field, value);
  }

  /**
   * Writes a property value on the target.
   *
   * <p>Implementations may resolve JavaBean setters when available and fall back to direct field
   * writes when appropriate. Unsupported writes should throw an explicit exception.
   *
   * @param o receiver object
   * @param field property name to resolve
   * @param value new property value
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time
   * @throws IllegalArgumentException when the name is unknown for the receiver
   */
  public static void setProperty(Object o, String field, Object value) {
    ReflectAOTServices.runtime().setProperty(o, field, value);
  }
}
