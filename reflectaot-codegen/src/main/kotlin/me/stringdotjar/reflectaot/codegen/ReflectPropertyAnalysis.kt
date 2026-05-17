package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.Type

/**
 * Derives whether a logical property name is readable or writable for validation and for choosing emit paths.
 *
 * This object bridges [TypeIntrospection.IntrospectedType] metadata and the rules enforced by [ReflectAOTCodegen] for
 * `Reflect.property` and `Reflect.setProperty`.
 */
object ReflectPropertyAnalysis {

  /**
   * Returns true when [name] can be read via `Reflect.property` on type [t].
   *
   * @param t Introspected receiver type including fields and bean metadata.
   * @param name User-facing property or field name from a scanned call site.
   * @return True when a public field exists with that name, or when a bean property exposes a readable path.
   */
  fun canReadPropertyName(t: TypeIntrospection.IntrospectedType, name: String): Boolean {
    if (name in t.fields) {
      return true
    }
    val p = t.properties.firstOrNull { it.name == name } ?: return false
    return beanReadable(p, t)
  }

  /**
   * Returns true when [name] can be written via `Reflect.setProperty` on type [t].
   *
   * @param t Introspected receiver type including writable fields and bean metadata.
   * @param name User-facing property or field name from a scanned call site.
   * @return True when a writable public field exists, or when a bean property exposes a setter path accepted by [beanWritableForEmit].
   */
  fun canWritePropertyName(t: TypeIntrospection.IntrospectedType, name: String): Boolean {
    if (name in t.fieldsWritable) {
      return true
    }
    val p = t.properties.firstOrNull { it.name == name } ?: return false
    return beanWritableForEmit(p, t)
  }

  /**
   * Returns true when [p] exposes a getter or a readable public field fallback on [t].
   *
   * @param p Bean property metadata entry.
   * @param t Introspected type that owns field visibility maps.
   * @return True when bytecode emission can read the property without violating visibility rules encoded in [t].
   */
  fun beanReadable(p: TypeIntrospection.BeanProperty, t: TypeIntrospection.IntrospectedType): Boolean {
    if (p.getterName != null && p.getterDesc != null) {
      return true
    }
    if (p.fieldName != null && p.fieldName in t.fields) {
      return true
    }
    return false
  }

  /**
   * Returns true when generated `setProperty` can emit a write using a single-argument setter and/or a non-final public field.
   *
   * @param p Bean property metadata entry.
   * @param t Introspected type that owns writable field maps.
   * @return True when a supported setter exists, or when a mapped public field is present in [TypeIntrospection.IntrospectedType.fieldsWritable].
   */
  fun beanWritableForEmit(p: TypeIntrospection.BeanProperty, t: TypeIntrospection.IntrospectedType): Boolean {
    if (p.setterName != null && p.setterDesc != null) {
      val args = Type.getArgumentTypes(p.setterDesc)
      if (args.size == 1) {
        return true
      }
    }
    return p.fieldName != null && p.fieldName in t.fieldsWritable
  }
}
