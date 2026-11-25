package io.phasetwo.keycloak.magic.auth.util;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.common.util.Time;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

/**
 * Throttled, asynchronous email dispatch for authenticators that send mail in response to
 * unauthenticated input (a submitted email address).
 *
 * <ul>
 *   <li>{@link #tryAcquire}: a cluster-wide per-user cooldown in {@link SingleUseObjectProvider}.
 *       It is keyed on the user, not the authentication session, so opening a fresh session does
 *       not reset it. Repeated submissions for one address send at most one email per window,
 *       across every authenticator that uses this class.
 *   <li>{@link #sendAsync}: template rendering and SMTP run on a small bounded executor, in their
 *       own session, after the request transaction commits. A slow or unresponsive mail relay can
 *       then no longer hold HTTP worker threads, and known and unknown addresses take about the
 *       same time to answer.
 * </ul>
 */
@JBossLog
public final class EmailDispatch {

  public static final String SEND_COOLDOWN_CONFIG_PROPERTY = "ext-email-send-cooldown";
  public static final int DEFAULT_SEND_COOLDOWN_SECONDS = 60;

  public static final ProviderConfigProperty SEND_COOLDOWN_PROPERTY;

  static {
    SEND_COOLDOWN_PROPERTY = new ProviderConfigProperty();
    SEND_COOLDOWN_PROPERTY.setType(ProviderConfigProperty.STRING_TYPE);
    SEND_COOLDOWN_PROPERTY.setName(SEND_COOLDOWN_CONFIG_PROPERTY);
    SEND_COOLDOWN_PROPERTY.setLabel("Email send cooldown (seconds)");
    SEND_COOLDOWN_PROPERTY.setHelpText(
        "Minimum seconds between emails sent to the same user, across all sessions and all"
            + " magic link / email OTP / activation authenticators. 0 disables. Default "
            + DEFAULT_SEND_COOLDOWN_SECONDS
            + ".");
    SEND_COOLDOWN_PROPERTY.setDefaultValue(String.valueOf(DEFAULT_SEND_COOLDOWN_SECONDS));
  }

  static final String COOLDOWN_KEY_PREFIX = "ext-email-send-cooldown:";

  /** Template attribute read by {@code resend-countdown.ftl}. */
  public static final String RESEND_COOLDOWN_ATTRIBUTE = "resendCooldownSeconds";

  static final String COUNTDOWN_STARTED_NOTE = "ext-email-countdown-started";

  // ponytail: fixed pool and bounded queue. When the queue is full, sends are dropped and logged,
  // the same outcome as an SMTP failure. Make the sizes configurable if legitimate volume ever
  // needs more.
  private static final int SEND_THREADS = 4;
  private static final int SEND_QUEUE_CAPACITY = 1000;

  private static final ThreadPoolExecutor EXECUTOR = newExecutor();

  private EmailDispatch() {}

  /** Reads the cooldown from an execution's config. Missing or invalid values use the default. */
  public static int cooldownSeconds(AuthenticatorConfigModel configModel) {
    Map<String, String> config = configModel == null ? null : configModel.getConfig();
    String val = config == null ? null : config.get(SEND_COOLDOWN_CONFIG_PROPERTY);
    if (val == null || val.isBlank()) return DEFAULT_SEND_COOLDOWN_SECONDS;
    try {
      return Math.max(0, Integer.parseInt(val.trim()));
    } catch (NumberFormatException e) {
      return DEFAULT_SEND_COOLDOWN_SECONDS;
    }
  }

  /**
   * Makes the page's Resend button count down from {@code seconds} before it can be used. Call this
   * on every render of the page, whether or not the account exists, so the countdown reveals
   * nothing about which addresses have accounts.
   */
  public static LoginFormsProvider withResendCountdown(LoginFormsProvider form, int seconds) {
    return form.setAttribute(RESEND_COOLDOWN_ATTRIBUTE, seconds);
  }

  /**
   * Seconds left on a Resend countdown that runs from the first render in this authentication
   * session, so re-rendering the page (a wrong code, a reload) does not restart it. Starts the
   * countdown if none is running. The start time is set for every visitor, account or not, so it
   * reveals nothing about which addresses have accounts. Call {@link #restartCountdown} when the
   * user asks for a new email.
   */
  public static int countdownRemaining(
      AuthenticationSessionModel authSession, int cooldownSeconds) {
    int now = Time.currentTime();
    String started = authSession.getAuthNote(COUNTDOWN_STARTED_NOTE);
    if (started == null) {
      authSession.setAuthNote(COUNTDOWN_STARTED_NOTE, Integer.toString(now));
      return cooldownSeconds;
    }
    return remainingSeconds(started, now, cooldownSeconds);
  }

  /** Makes the next {@link #countdownRemaining} call start a fresh countdown. */
  public static void restartCountdown(AuthenticationSessionModel authSession) {
    authSession.removeAuthNote(COUNTDOWN_STARTED_NOTE);
  }

  static int remainingSeconds(String startedAt, int now, int cooldownSeconds) {
    try {
      // clamped, so a clock that moved backwards never stretches the countdown past the cooldown
      return Math.clamp(
          cooldownSeconds - ((long) now - Integer.parseInt(startedAt)), 0, cooldownSeconds);
    } catch (NumberFormatException e) {
      return cooldownSeconds;
    }
  }

  /**
   * Atomically claims the send slot for this user. Returns false if an email went to the user
   * within the cooldown window. A cooldown of 0 or less always returns true.
   */
  public static boolean tryAcquire(
      KeycloakSession session, RealmModel realm, UserModel user, int cooldownSeconds) {
    if (cooldownSeconds <= 0) return true;
    return session
        .getProvider(SingleUseObjectProvider.class)
        .putIfAbsent(COOLDOWN_KEY_PREFIX + realm.getId() + ":" + user.getId(), cooldownSeconds);
  }

  /**
   * Runs {@code send} after the current transaction commits, on a background thread, in a new
   * session whose context carries the same realm, client and authentication session. Email locale
   * and theme therefore resolve as they would during the request. Nothing is sent if the request
   * transaction rolls back.
   *
   * <p>The user passed to {@code send} is reloaded in the background session. Do not capture
   * request-scoped models (sessions, user adapters, auth sessions) in {@code send}; capture plain
   * values instead.
   */
  public static void sendAsync(
      AuthenticationFlowContext context,
      UserModel user,
      String description,
      BiConsumer<KeycloakSession, UserModel> send) {
    KeycloakSession session = context.getSession();
    KeycloakSessionFactory factory = session.getKeycloakSessionFactory();
    String realmId = context.getRealm().getId();
    String userId = user.getId();
    AuthenticationSessionModel authSession = context.getAuthenticationSession();
    String clientUuid = authSession.getClient().getId();
    String rootSessionId = authSession.getParentSession().getId();
    String tabId = authSession.getTabId();

    Runnable job =
        () -> {
          try {
            KeycloakModelUtils.runJobInTransaction(
                factory,
                s -> {
                  RealmModel realm = s.realms().getRealm(realmId);
                  if (realm == null) return;
                  s.getContext().setRealm(realm);
                  ClientModel client = s.clients().getClientById(realm, clientUuid);
                  if (client != null) {
                    s.getContext().setClient(client);
                    RootAuthenticationSessionModel root =
                        s.authenticationSessions()
                            .getRootAuthenticationSession(realm, rootSessionId);
                    AuthenticationSessionModel as =
                        root == null ? null : root.getAuthenticationSession(client, tabId);
                    if (as != null) s.getContext().setAuthenticationSession(as);
                  }
                  UserModel u = s.users().getUserById(realm, userId);
                  if (u == null) {
                    log.warnf("Skipping %s email: user %s no longer exists", description, userId);
                    return;
                  }
                  send.accept(s, u);
                });
          } catch (RuntimeException e) {
            log.errorf(e, "Failed to send %s email to user %s", description, userId);
          }
        };

    session
        .getTransactionManager()
        .enlistAfterCompletion(
            new AbstractKeycloakTransaction() {
              @Override
              protected void commitImpl() {
                try {
                  EXECUTOR.execute(job);
                } catch (RejectedExecutionException e) {
                  log.warnf(
                      "Dropping %s email to user %s: send queue is full (%d pending)",
                      description, userId, EXECUTOR.getQueue().size());
                }
              }

              @Override
              protected void rollbackImpl() {
                // the request failed; there is nothing to send
              }
            });
  }

  private static ThreadPoolExecutor newExecutor() {
    AtomicInteger seq = new AtomicInteger();
    ThreadFactory threads =
        r -> {
          Thread t = new Thread(r, "magic-link-email-" + seq.incrementAndGet());
          t.setDaemon(true);
          return t;
        };
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            SEND_THREADS,
            SEND_THREADS,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY),
            threads,
            new ThreadPoolExecutor.AbortPolicy());
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
