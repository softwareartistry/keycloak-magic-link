import { waitForMail } from "../support/mailhog";
import {mailhogBaseUrl, testRealmLoginUri} from "../fixtures/uri";
import { user1, } from "../fixtures/users";

describe('Basic tests with the Magic Link Provider', () => {
   it('User who exists in the realm should be able to log into the account console', () => {
      cy.visit(testRealmLoginUri);
      cy.get('#username').type(user1.username);
      cy.get('#kc-login').click();

      cy.contains('Check your email, and click on the link to log in!');

      cy.task('log', 'Mailhog base URL is: ' + mailhogBaseUrl);

      cy.wrap(null).then(() => {
         return fetchLoginEmail(user1.username)
      })
          .then((mail) => {
              const body =
                  mail.Content.Body ||
                  mail.Content.Headers['Content-Type']?.includes('text/html')
                      ? mail.Content.Body
                      : ''

              expect(body).to.contain('Someone requested a login link to Test Realm Display Name')
              expect(body).to.contain('Click to log in.')

             const loginLink = extractLoginLink(body)
             cy.log(`Login link: ${loginLink}`)
             cy.visit(loginLink)
          });
      cy.url().should('contain', 'test-realm')
      cy.contains('Personal')
   })

   it('Resend counts down the send cooldown, then sends a second magic link that can be used to log in', () => {
      // The previous test emailed user1; let the fixture's 5s per-user send cooldown pass first
      cy.wait(5000);

      // Start with empty inbox so we can assert exact counts
      cy.request('DELETE', mailhogBaseUrl + '/api/v1/messages');

      // Submit email - arrive at waiting page
      cy.visit(testRealmLoginUri);
      cy.get('#username').type(user1.username);
      cy.get('#kc-login').click();
      cy.contains('Check your email, and click on the link to log in!');
      cy.wrap(null).then(() => fetchLoginEmail(user1.username));

      // Resend is disabled while the fixture's 5s send cooldown runs
      cy.get('#kc-resend').should('be.disabled').and('contain', 's)');

      // A fresh session for the same user inside the cooldown gets the same page but no email
      cy.clearCookies();
      cy.visit(testRealmLoginUri);
      cy.get('#username').type(user1.username);
      cy.get('#kc-login').click();
      cy.contains('Check your email, and click on the link to log in!');
      cy.wait(2000);
      cy.request(mailhogBaseUrl + '/api/v2/messages').then((response) => {
         expect(response.body.total, 'emails inside the cooldown').to.equal(1);
      });

      // Once the countdown ends, Resend sends a second email
      cy.get('#kc-resend', { timeout: 10000 }).should('not.be.disabled').click();
      cy.contains('Check your email, and click on the link to log in!');

      // Fetch second (most recent) email - Mailhog returns newest first
      cy.wrap(null)
          .then(() => waitForMail(user1.username, 'Log in to Test Realm Display Name', 'resent login email', 2))
          .then((mail) => {
              const body = mail.Content.Body

              expect(body).to.contain('Someone requested a login link to Test Realm Display Name')

              const loginLink = extractLoginLink(body)
              cy.log(`Resent login link: ${loginLink}`)
              cy.visit(loginLink)
          });

      // Exactly the original and the resent email: the in-cooldown submit sent nothing
      cy.request(mailhogBaseUrl + '/api/v2/messages').then((response) => {
          expect(response.body.total).to.equal(2);
      });

      cy.url().should('contain', 'test-realm');
      cy.contains('Personal');
   });

   it('Non-existent user should see the check your email message but no email should be sent', () => {
      // 1. delete everything from mailhog
      cy.request('DELETE', mailhogBaseUrl + '/api/v1/messages');

      // 2. try to log in with non-existent email
      cy.visit(testRealmLoginUri);
      cy.get('#username').type('nonexistent@phasetwo.io');
      cy.get('#kc-login').click();

      // 3. verify "check your email" message is present
      cy.contains('Check your email, and click on the link to log in!');

      // 4. wait ~5 secs
      cy.wait(5000);

      // 5. verify no email arrived in mailhog
      cy.request(mailhogBaseUrl + '/api/v2/messages').then((response) => {
         expect(response.body.total).to.equal(0);
         expect(response.body.items).to.have.length(0);
      });
   });
});

function fetchLoginEmail(toEmail) {
    return waitForMail(toEmail, 'Log in to Test Realm Display Name', 'login email')
}

function extractLoginLink(body) {

   const linkRegex = /https?:\/\/[^\s"]+/g
   const links = decodeQuotedPrintable(body).match(linkRegex)

   expect(links, 'login links').to.not.be.empty
   return links[0]
}

// Keycloak sends the e-mail quoted-printable encoded. A soft line break (a "="
// at the end of a line) can fall inside the magic link and truncate it, and "="
// itself is escaped as "=3D". Un-fold and decode before extracting the URL.
function decodeQuotedPrintable(body) {
   if (!/=\r?\n/.test(body)) {
      return body
   }

   return body
       .replace(/=\r?\n/g, '')
       .replace(/=([0-9A-Fa-f]{2})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
}
