import { waitForMail } from "../support/mailhog";
import { mailhogBaseUrl, emailOtpBruteForceRealmLoginUri } from "../fixtures/uri";
import { user1 } from "../fixtures/users";

// This realm's browser flow uses `reset-credentials-choose-user`, which is
// enumeration resistant: it sets the attempted username and calls success() for
// every input, clearing the user when no account matches. The Email OTP
// authenticator therefore runs with a null user for unknown emails. With brute
// force protection enabled on the realm, submitting the OTP form in that state
// used to throw an NPE inside AuthenticatorUtils.getDisabledByBruteForceEventError().
describe('Email OTP authenticator with brute force protection enabled', () => {
    it('Submitting a code for a non-existent user shows an error instead of failing the flow', () => {
        cy.request('DELETE', mailhogBaseUrl + '/api/v1/messages');

        cy.visit(emailOtpBruteForceRealmLoginUri);
        cy.get('#username').type('nonexistent@phasetwo.io');
        cy.get('#kc-reset-password-form [type="submit"]').click();

        // The unknown user is forwarded to the OTP authenticator rather than
        // being rejected, so the account's existence is not disclosed.
        cy.contains('Enter access code');
        cy.contains('nonexistent@phasetwo.io');

        cy.get('#otp').type('000000');
        cy.get('#kc-submit').click();

        cy.contains('Invalid access code');
        cy.contains('Enter access code');
        cy.contains('We are sorry').should('not.exist');
        cy.contains('Unexpected error').should('not.exist');

        // Resending also runs after the brute force check. The button counts down the send
        // cooldown (5s in this fixture) before it can be used.
        cy.get('#kc-resend', { timeout: 10000 }).should('not.be.disabled').click();
        cy.contains('Enter access code');
        cy.contains('We are sorry').should('not.exist');

        cy.request(mailhogBaseUrl + '/api/v2/messages').then((response) => {
            expect(response.body.total).to.equal(0);
            expect(response.body.items).to.have.length(0);
        });
    });

    it('An existing user can still log in with the code emailed to them', () => {
        cy.request('DELETE', mailhogBaseUrl + '/api/v1/messages');

        cy.visit(emailOtpBruteForceRealmLoginUri);
        cy.get('#username').type(user1.username);
        cy.get('#kc-reset-password-form [type="submit"]').click();

        cy.contains('Enter access code');

        cy.wrap(null).then(() => {
            return fetchOtpEmail(user1.username)
        })
            .then((mail) => {
                const body = mail.Content.Body;
                expect(body).to.contain('Someone requested a one-time-password to login to Email OTP Brute Force Realm');

                const code = extractOtpCode(body);
                cy.log(`OTP code: ${code}`);

                cy.get('#otp').type(code);
                cy.get('#kc-submit').click();
            });

        cy.url().should('contain', 'email-otp-brute-force-realm');
        cy.contains('Personal');
    });
});

function fetchOtpEmail(toEmail) {
    return waitForMail(toEmail, 'Your access code for', 'otp email')
}

function extractOtpCode(body) {
    const match = body.match(/Code:\s*(\d{6})/)
    expect(match, 'otp code').to.not.be.null
    return match[1]
}
