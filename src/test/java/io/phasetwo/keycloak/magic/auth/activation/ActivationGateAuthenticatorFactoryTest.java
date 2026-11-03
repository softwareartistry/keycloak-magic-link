package io.phasetwo.keycloak.magic.auth.activation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;

class ActivationGateAuthenticatorFactoryTest {

  // The gate only identifies the user. As an ALTERNATIVE its success would satisfy the flow on its
  // own, so a login with nothing but an email address would skip the password step.
  @Test
  void gateCannotBeConfiguredAsAlternative() {
    assertArrayEquals(
        new Requirement[] {Requirement.REQUIRED, Requirement.DISABLED},
        new ActivationGateAuthenticatorFactory().getRequirementChoices());
  }
}
