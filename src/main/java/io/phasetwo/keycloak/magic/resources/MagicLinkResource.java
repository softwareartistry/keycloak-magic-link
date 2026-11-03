package io.phasetwo.keycloak.magic.resources;

import static io.phasetwo.keycloak.magic.MagicLink.MAGIC_LINK;

import io.phasetwo.keycloak.magic.MagicLink;
import io.phasetwo.keycloak.magic.auth.magic.MagicLinkActionToken;
import io.phasetwo.keycloak.magic.representation.MagicLinkRequest;
import io.phasetwo.keycloak.magic.representation.MagicLinkResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.OptionalInt;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

@JBossLog
public final class MagicLinkResource extends AbstractAdminResource {

  public MagicLinkResource(KeycloakSession session) {
    super(session);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public MagicLinkResponse createMagicLink(final MagicLinkRequest rep) {
    String emailOrUsername = rep.getEmail();
    boolean forceCreate = rep.isForceCreate();
    boolean sendEmail = rep.isSendEmail();

    if (rep.getUsername() != null) {
      emailOrUsername = rep.getUsername();
      forceCreate = false;
    }

    UserModel user = MagicLink.findUser(session, realm, emailOrUsername);

    // Same 403 whether or not the user exists, so a caller who may not manage a user cannot
    // probe which usernames/emails are registered.
    if (user != null ? !permissions.users().canManage(user) : !permissions.users().canManage())
      throw new ForbiddenException("magic link requires manage-users");
    if (user == null && !forceCreate)
      throw new NotFoundException(
          String.format(
              "User with email/username %s not found, and forceCreate is off.", emailOrUsername));

    ClientModel client = session.clients().getClientByClientId(realm, rep.getClientId());
    if (client == null)
      throw new NotFoundException(String.format("Client with ID %s not found.", rep.getClientId()));
    if (!MagicLink.validateRedirectUri(session, rep.getRedirectUri(), client))
      throw new BadRequestException(
          String.format("redirectUri %s disallowed by client.", rep.getRedirectUri()));

    // previous lookup failed, permission to manage all users is verified, so try to create the user
    // now
    if (user == null) {
      user =
          MagicLink.getOrCreate(
              session,
              realm,
              emailOrUsername,
              true,
              rep.isUpdateProfile(),
              rep.isUpdatePassword(),
              MagicLink.registerEvent(event, MAGIC_LINK));
      // getOrCreate() never creates a user from a blank email/username
      if (user == null)
        throw new NotFoundException(
            String.format("User with email/username '%s' not found.", emailOrUsername));
    }

    if (user.getEmail() == null || user.getEmail().isBlank()) {
      sendEmail = false;
    }

    MagicLinkActionToken token =
        MagicLink.createActionToken(
            user,
            rep.getClientId(),
            rep.getRedirectUri(),
            OptionalInt.of(rep.getExpirationSeconds()),
            rep.getScope(),
            rep.getNonce(),
            rep.getState(),
            rep.getCodeChallenge(),
            rep.getCodeChallengeMethod(),
            rep.getRememberMe(),
            rep.getActionTokenPersistent(),
            rep.getResponseMode());
    String link = MagicLink.linkFromActionToken(session, realm, token);
    boolean sent = false;
    if (sendEmail) {
      sent = MagicLink.sendMagicLinkEmail(session, user, link, rep.getExpirationSeconds() / 60);
      log.debugf("sent email to %s? %b. Link? %s", rep.getEmail(), sent, link);
    }

    MagicLinkResponse resp = new MagicLinkResponse();
    resp.setUserId(user.getId());
    resp.setLink(link);
    resp.setSent(sent);

    return resp;
  }
}
