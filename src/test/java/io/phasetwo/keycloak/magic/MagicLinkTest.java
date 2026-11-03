package io.phasetwo.keycloak.magic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MagicLinkTest {

  @ParameterizedTest
  @ValueSource(strings = {"alice@phasetwo.io", "a.b+tag@sub.phasetwo.io"})
  void isValidEmailAcceptsBareAddresses(String email) {
    assertTrue(MagicLink.isValidEmail(email));
  }

  // jakarta.mail's InternetAddress accepts display-name and comment forms, so "create nonexistent
  // user" kept creating accounts from them that Keycloak's mail sender then refuses to email.
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "Eve <alice@phasetwo.io>",
        "alice@phasetwo.io (x)",
        "\"x\" <alice@phasetwo.io>",
        "not-an-email"
      })
  void isValidEmailRejectsAnythingButABareAddress(String email) {
    assertFalse(MagicLink.isValidEmail(email));
  }
}
