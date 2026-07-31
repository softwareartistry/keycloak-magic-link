<#import "template.ftl" as layout>
<@layout.emailLayout>
${kcSanitize(msg("magicLinkContinuationBodyHtml", realmName, magicLink, linkExpirationText!""))?no_esc}
</@layout.emailLayout>
