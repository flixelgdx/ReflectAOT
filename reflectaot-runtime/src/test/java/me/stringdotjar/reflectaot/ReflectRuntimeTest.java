package me.stringdotjar.reflectaot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReflectRuntimeTest {

  @BeforeEach
  void installStubRuntime() {
    ReflectAOTServices.install(new ReflectAOTStubRuntime());
  }

  @Test
  void compareOrdersNullsAndNumbers() {
    assertTrue(Reflect.compare(null, 1) < 0);
    assertTrue(Reflect.compare(1, null) > 0);
    assertEquals(0, Reflect.compare(3, 3));
    assertTrue(Reflect.compare(1, 2) < 0);
  }
}
