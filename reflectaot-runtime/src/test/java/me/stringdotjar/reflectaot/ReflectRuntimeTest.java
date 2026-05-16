package me.stringdotjar.reflectaot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReflectRuntimeTest {

  @BeforeEach
  void installStubRuntime() {
    ReflectAOTServices.install(new ReflectAOTStubRuntime());
  }

  @Test
  void isObjectTreatsStringsAsObjects() {
    assertTrue(Reflect.isObject("literal"));
    assertTrue(Reflect.isObject(new String("constructed")));
    assertFalse(Reflect.isObject(null));
    assertFalse(Reflect.isObject(42));
    assertFalse(Reflect.isObject(true));
    assertFalse(Reflect.isObject('x'));
  }

  @Test
  void forEachApisDelegateToDefaultDispatchWhenStub() {
    assertThrows(
        UnsupportedOperationException.class, () -> Reflect.forEachField("x", (n, v) -> {}));
    assertThrows(
        UnsupportedOperationException.class, () -> Reflect.forEachProperty("x", (n, v) -> {}));
    assertThrows(
        UnsupportedOperationException.class,
        () -> Reflect.forEachMethod(String.class, (n, id) -> {}));
  }

  @Test
  void compareOrdersNullsAndNumbers() {
    assertTrue(Reflect.compare(null, 1) < 0);
    assertTrue(Reflect.compare(1, null) > 0);
    assertEquals(0, Reflect.compare(3, 3));
    assertTrue(Reflect.compare(1, 2) < 0);
  }
}
