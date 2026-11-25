package io.phasetwo.keycloak.magic.auth.util;

import static io.phasetwo.keycloak.magic.auth.util.EmailDispatch.DEFAULT_SEND_COOLDOWN_SECONDS;
import static io.phasetwo.keycloak.magic.auth.util.EmailDispatch.SEND_COOLDOWN_CONFIG_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;

class EmailDispatchTest {

  private SingleUseObjectProvider singleUse;
  private KeycloakSession session;
  private final Set<String> claimed = new HashSet<>();

  @BeforeEach
  void setUp() {
    singleUse = mock(SingleUseObjectProvider.class);
    // putIfAbsent semantics: the first claim of a key wins, later ones fail
    when(singleUse.putIfAbsent(anyString(), anyLong()))
        .thenAnswer(inv -> claimed.add(inv.getArgument(0)));
    session = mock(KeycloakSession.class);
    when(session.getProvider(SingleUseObjectProvider.class)).thenReturn(singleUse);
  }

  @Test
  void secondSendToSameUserWithinWindowIsThrottled() {
    RealmModel realm = realm("r1");
    UserModel alice = user("alice");

    assertTrue(EmailDispatch.tryAcquire(session, realm, alice, 60));
    assertFalse(EmailDispatch.tryAcquire(session, realm, alice, 60));
    // both attempts claim the same per-realm, per-user key
    verify(singleUse, times(2)).putIfAbsent(EmailDispatch.COOLDOWN_KEY_PREFIX + "r1:alice", 60L);
  }

  @Test
  void differentUsersAndRealmsDoNotShareASlot() {
    assertTrue(EmailDispatch.tryAcquire(session, realm("r1"), user("alice"), 60));
    assertTrue(EmailDispatch.tryAcquire(session, realm("r1"), user("bob"), 60));
    assertTrue(EmailDispatch.tryAcquire(session, realm("r2"), user("alice"), 60));
  }

  @Test
  void zeroCooldownDisablesThrottleWithoutTouchingTheStore() {
    UserModel alice = user("alice");
    assertTrue(EmailDispatch.tryAcquire(session, realm("r1"), alice, 0));
    assertTrue(EmailDispatch.tryAcquire(session, realm("r1"), alice, 0));
    verifyNoInteractions(singleUse);
  }

  @Test
  void missingConfigUsesDefaultCooldown() {
    assertEquals(DEFAULT_SEND_COOLDOWN_SECONDS, EmailDispatch.cooldownSeconds(null));
    assertEquals(DEFAULT_SEND_COOLDOWN_SECONDS, EmailDispatch.cooldownSeconds(config(null)));
  }

  @ParameterizedTest
  @CsvSource({"30,30", "' 45 ',45", "0,0", "-5,0", "abc,60", "'',60"})
  void cooldownIsParsedFromConfig(String raw, int expected) {
    assertEquals(expected, EmailDispatch.cooldownSeconds(config(raw)));
  }

  @ParameterizedTest
  @CsvSource({
    "1000,1000,60", // just started
    "1000,1045,15", // re-rendered 45s later
    "1000,1060,0", // cooldown over
    "1000,1500,0",
    "1000,900,60", // clock moved backwards: never more than the cooldown
    "abc,1000,60", // unreadable note: full cooldown
  })
  void countdownCountsOnlyWhatIsLeft(String startedAt, int now, int expected) {
    assertEquals(expected, EmailDispatch.remainingSeconds(startedAt, now, 60));
  }

  private static AuthenticatorConfigModel config(String cooldown) {
    Map<String, String> map = new HashMap<>();
    if (cooldown != null) map.put(SEND_COOLDOWN_CONFIG_PROPERTY, cooldown);
    AuthenticatorConfigModel model = new AuthenticatorConfigModel();
    model.setConfig(map);
    return model;
  }

  private static RealmModel realm(String id) {
    RealmModel realm = mock(RealmModel.class);
    when(realm.getId()).thenReturn(id);
    return realm;
  }

  private static UserModel user(String id) {
    UserModel user = mock(UserModel.class);
    when(user.getId()).thenReturn(id);
    return user;
  }
}
