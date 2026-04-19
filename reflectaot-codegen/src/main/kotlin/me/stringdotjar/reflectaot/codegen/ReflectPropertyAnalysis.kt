package me.stringdotjar.reflectaot.codegen

import org.objectweb.asm.Type

/**
 * Derives which bean names are actually readable/writable from generated helpers, for validation and emission.
 */
object ReflectPropertyAnalysis {

  fun canReadPropertyName(t: TypeIntrospection.IntrospectedType, name: String): Boolean {
    if (name in t.fields) {
      return true
    }
    val p = t.properties.firstOrNull { it.name == name } ?: return false
    return beanReadable(p, t)
  }

  fun canWritePropertyName(t: TypeIntrospection.IntrospectedType, name: String): Boolean {
    if (name in t.fieldsWritable) {
      return true
    }
    val p = t.properties.firstOrNull { it.name == name } ?: return false
    return beanWritableForEmit(p, t)
  }

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
   * True when generated `setProperty` can perform a write (single-arg setter and/or assignable public non-final field).
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
