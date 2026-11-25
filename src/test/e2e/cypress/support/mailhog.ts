import { mailhogBaseUrl } from "../fixtures/uri";

/**
 * Polls MailHog until at least `count` messages to `toEmail` with a subject containing `subject`
 * exist, then yields the newest one (MailHog lists newest first). Keycloak sends these emails
 * after the request commits, so they can land a moment after the page has rendered; reading the
 * inbox once would race the send.
 */
export function waitForMail(
  toEmail: string,
  subject: string,
  label: string,
  count = 1,
  attemptsLeft = 20,
): Cypress.Chainable<any> {
  return cy
    .request({ method: "GET", url: mailhogBaseUrl + "/api/v2/messages", retryOnStatusCodeFailure: true })
    .then((res) => {
      const matches = res.body.items.filter((msg) => {
        const to = msg.To.map((t) => t.Mailbox + "@" + t.Domain);
        const subj = msg.Content.Headers.Subject?.[0] || "";
        return to.includes(toEmail) && subj.includes(subject);
      });
      if (matches.length >= count) return matches[0];
      if (attemptsLeft <= 1) {
        expect(matches.length, label).to.be.at.least(count);
        return undefined;
      }
      return cy.wait(500).then(() => waitForMail(toEmail, subject, label, count, attemptsLeft - 1));
    });
}
