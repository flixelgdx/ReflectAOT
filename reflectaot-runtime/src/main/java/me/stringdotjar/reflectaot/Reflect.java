package me.stringdotjar.reflectaot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class that provides a comprehensive runtime interface for reflection-like operations
 * in environments that require ahead-of-time (AOT) compilation, such as GraalVM Native Image or TeaVM.
 * <p>
 * Rather than relying on dynamic reflection, which is often unavailable or restricted in such environments,
 * this class acts as a facade around code-generated or build-time resolved metadata and references,
 * enabling type and method inspection, comparison, and dispatch at runtime without incurred reflection costs.
 * <p>
 * Main responsibilities and features include:
 * <ul>
 *   <li>
 *     Ensuring early initialization of generated code and bootstrapped class metadata so that
 *     core reflective services are available as soon as possible.
 *   </li>
 *   <li>
 *     Providing type-safe static APIs for:
 *     <ul>
 *       <li>Comparing objects, primitives, and string values even if their implementation relies on customized or generated logic.</li>
 *       <li>Comparing build-time method identifiers for logical equivalence (crossing the dynamic and static boundary at callsites).</li>
 *     </ul>
 *   </li>
 *   <li>
 *     Delegating calls to an installed runtime service provider ({@link ReflectAOTRuntime}) which abstracts over the actual implementation—
 *     either the code generator's output or a fallback shim in test/non-AOT scenarios.
 *   </li>
 * </ul>
 * <p>
 * This class is intentionally final with static entry points only. Varargs forms of
 * {@link #callMethod(Object, int, Object...)} reuse a {@link ThreadLocal} argument list buffer per
 * thread (see implementation) so hot loops do not allocate a fresh {@link ArrayList} on each call.
 * Primitive arguments are still boxed into {@code Object} varargs arrays as usual for Java.
 * It is safe to use across threads and supports a pure-Java fallback suitable for build scans and
 * generated runtime code.
 * <p>
 * <strong>NOTE:</strong> The presence of this class does not guarantee the existence or correctness
 * of generated metadata; ensure code generation phases run as required (e.g., via the Gradle plugin).
 *
 * <h2>Typical Usage</h2>
 *
 * <pre>{@code
 * // Compare two values.
 * int order = Reflect.compare(a, b);
 *
 * // Compare two method identifiers.
 * ReflectMethodId methodId1 = Reflect.method(Foo.class, "method");
 * ReflectMethodId methodId2 = Reflect.method(Foo.class, "method", "(I)V"); // JVM descriptor, usually not required unless there are multiple overloads.
 * boolean sameMethod = Reflect.compareMethods(methodId1, methodId2);
 *
 * // Copy an object.
 * T copied = Reflect.copy(o);
 *
 * // Read a field value.
 * Object value = Reflect.field(o, "field");
 *
 * // Get available fields.
 * String[] fields = Reflect.fields(o);
 *
 * // Invoke a method.
 * Object result = Reflect.callMethod(o, methodId, args);
 *
 * // Read a property value via JavaBean conventions.
 * Object value = Reflect.property(o, "property");
 *
 * // Check if a field exists.
 * boolean hasField = Reflect.hasField(o, "field");
 *
 * // Check if a value is an enum constant.
 * boolean isEnum = Reflect.isEnumValue(value);
 *
 * // Check if a value is a function.
 * boolean isFunction = Reflect.isFunction(value);
 *
 * // Check if a value is an object.
 * boolean isObject = Reflect.isObject(value);
 *
 * // Write a field value directly.
 * Reflect.setField(o, "field", value);
 *
 * // Write a property value via JavaBean conventions.
 * Reflect.setProperty(o, "property", value);
 * }</pre>
 */
public final class Reflect {

  /**
   * Reuses one {@link ArrayList} per thread for {@link #callMethod(Object, int, Object...)} /
   * {@link #callMethod(Object, ReflectMethodId, Object...)}. Nested calls allocate a spare list so
   * downstream code cannot observe a reused list across re-entrant dispatch.
   */
  private static final ThreadLocal<VarargsArgList> VARARGS_BUFFER = ThreadLocal.withInitial(VarargsArgList::new);

  private static final class VarargsArgList {
    private final ArrayList<Object> list = new ArrayList<>(8);
    private int depth;

    private Object invoke(Object o, int methodId, Object[] args) {
      if (args == null || args.length == 0) {
        return ReflectAOTServices.runtime().callMethod(o, methodId, Collections.emptyList());
      }
      depth++;
      try {
        if (depth > 1) {
          ArrayList<Object> nested = new ArrayList<>(args.length);
          Collections.addAll(nested, args);
          return ReflectAOTServices.runtime().callMethod(o, methodId, nested);
        }
        list.clear();
        Collections.addAll(list, args);
        return ReflectAOTServices.runtime().callMethod(o, methodId, list);
      } finally {
        depth--;
        if (depth == 0) {
          list.clear();
        }
      }
    }
  }

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
   * #method(Class, String, String)} (no primitive {@code int} cast at call sites).
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
    return VARARGS_BUFFER.get().invoke(o, methodId, args);
  }

  /**
   * Same as {@link #callMethod(Object, int, Object...)} using {@link ReflectMethodId}.
   *
   * @param o receiver object
   * @param methodId build-resolved method token
   * @param args invocation arguments (which may be {@code null} or empty)
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
  public static ReflectMethodId method(Class<?> clazz, String name, String descriptor) {
    if (clazz == null) {
      throw new IllegalArgumentException("clazz");
    }
    if (name == null) {
      throw new IllegalArgumentException("name");
    }
    if (descriptor == null) {
      throw new IllegalArgumentException("descriptor");
    }
    return ReflectAOTServices.resolveMethod(clazz, name, descriptor);
  }

  /**
   * Same as {@link #method(Class, String, String)} but the JVM method descriptor is inferred at
   * build time when exactly one public instance method with {@code name} exists on {@code clazz}
   * (including supertypes).
   *
   * <p>If several overloads share the same name, the Gradle task fails with a list of descriptors;
   * use {@link #method(Class, String, String)} for those cases.
   *
   * <p>{@code clazz} and {@code name} must be compile-time constants (typically {@code Foo.class}
   * and a string literal) so the scanner can validate the call.
   *
   * @param clazz receiver class literal
   * @param name JVM method name
   * @return opaque method token for {@link #callMethod(Object, ReflectMethodId, Object...)}
   */
  public static ReflectMethodId method(Class<?> clazz, String name) {
    if (clazz == null) {
      throw new IllegalArgumentException("clazz");
    }
    if (name == null) {
      throw new IllegalArgumentException("name");
    }
    return ReflectAOTServices.resolveMethod(clazz, name);
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
  public static Object property(Object o, String field) {
    return ReflectAOTServices.runtime().property(o, field);
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
   * @throws UnsupportedOperationException when ReflectAOT has no dispatch for the receiver's concrete type (same as
   *     other Reflect entry points), or when using the stub runtime
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
   * Returns whether a value should be treated as a callable.
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
   * <p>If a field is immutable, final, unsupported, or cannot be written safely, an exception will be thrown.
   *
   * @param o Receiver object.
   * @param field Field name to write.
   * @param value New value to store.
   * @throws UnsupportedOperationException When the receiver type was not specialized at build time.
   * @throws IllegalArgumentException When the name is unknown for the receiver.
   */
  public static void setField(Object o, String field, Object value) {
    ReflectAOTServices.runtime().setField(o, field, value);
  }

  /**
   * Writes a property value on the target.
   *
   * <p>Resolve JavaBean setters when available and falls back to direct field
   * writes when appropriate. Unsupported writes will throw an exception.
   *
   * @param o receiver object
   * @param field property name to resolve
   * @param value new property value
   * @throws UnsupportedOperationException when the receiver type was not specialized at build time (or the stub
   *     runtime is installed)
   * @throws IllegalArgumentException when the name is unknown or not writable for the receiver (dynamic callers only;
   *     the Gradle plugin rejects invalid constant names at build time)
   */
  public static void setProperty(Object o, String field, Object value) {
    ReflectAOTServices.runtime().setProperty(o, field, value);
  }
}
