import { mailhogBaseUrl, emailOtpBruteForceRealmLoginUri } from "../fixtures/uri";
import { user1 } from "../fixtures/users";

// A correct code must clear the brute force counters, as Keycloak's own OTP form
// does. Keycloak only clears them for credential types the flow recorded, and
// the Email OTP authenticator used to call the untyped success(), so every
// earlier wrong code stayed counted. The counter is asserted from the Java test
// via the admin attack-detection endpoint.
describe('A correct Email OTP code resets the brute force counters', () => {
    it('One wrong code followed by the right one logs the user in', () => {
        cy.request('DELETE', mailhogBaseUrl + '/api/v1/messages');

        cy.visit(emailOtpBruteForceRealmLoginUri);
        cy.get('#username').type(user1.username);
        cy.get('#kc-reset-password-form [type="submit"]').click();

        cy.contains('Enter access code');

        cy.wrap(null).then(() => {
            return fetchOtpEmail(user1.username)
        })
            .then((mail) => {
                const code = extractOtpCode(mail.Content.Body);
                cy.log(`OTP code: ${code}`);

                cy.get('#otp').clear().type(code === '000000' ? '999999' : '000000');
                cy.get('#kc-submit').click();
                cy.contains('Invalid access code');

                cy.get('#otp').clear().type(code);
                cy.get('#kc-submit').click();
            });

        cy.url().should('contain', 'email-otp-brute-force-realm');
        cy.contains('Personal');
    });
});

function fetchOtpEmail(toEmail) {
    return cy
        .request({
            method: 'GET',
            url: mailhogBaseUrl + '/api/v2/messages',
            retryOnStatusCodeFailure: true,
        })
        .then((res) => {
            const messages = res.body.items

            const mail = messages.find((msg) => {
                const to = msg.To.map((t) => t.Mailbox + '@' + t.Domain)
                const subject = msg.Content.Headers.Subject?.[0] || ''

                return (
                    to.includes(toEmail) &&
                    subject.includes('Your access code for')
                )
            })

            expect(mail, 'otp email').to.exist
            return mail
        })
}

function extractOtpCode(body) {
    const match = body.match(/Code:\s*(\d{6})/)
    expect(match, 'otp code').to.not.be.null
    return match[1]
}
