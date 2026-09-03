package com.bank.platform.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IbanTest {

  @Test
  void officialExamplesValidate() {
    assertTrue(Iban.isValid("DE89370400440532013000"));
    assertTrue(Iban.isValid("GB29NWBK60161331926819"));
  }

  @Test
  void shapeOnlyStringsAreRejected() {
    assertFalse(Iban.isValid("DE89370400440532013001"));
    assertFalse(Iban.isValid("nope"));
    assertFalse(Iban.isValid(null));
    assertFalse(Iban.isValid("DE89"));
  }

  @Test
  void generatedIbansAreUniqueAndValid() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      String iban = Iban.generate();
      assertTrue(iban.startsWith("DE"));
      assertEquals(22, iban.length());
      assertTrue(Iban.isValid(iban));
      assertTrue(seen.add(iban));
    }
  }

  @Test
  void uniqueOrThrowRetriesThenGivesUp() {
    String fresh = Iban.uniqueOrThrow(candidate -> false, 3);
    assertTrue(Iban.isValid(fresh));
    java.util.concurrent.atomic.AtomicBoolean taken = new java.util.concurrent.atomic.AtomicBoolean(true);
    String second = Iban.uniqueOrThrow(candidate -> taken.getAndSet(false), 3);
    assertTrue(Iban.isValid(second));
    assertThrows(IllegalStateException.class,
        () -> Iban.uniqueOrThrow(candidate -> true, 2));
  }
}
