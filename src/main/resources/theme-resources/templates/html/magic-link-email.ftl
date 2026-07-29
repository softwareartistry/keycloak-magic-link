<#import "template.ftl" as layout>
<@layout.emailLayout>
${kcSanitize(msg("magicLinkBodyHtml", realmName, magicLink, linkExpirationText!""))?no_esc}
</@layout.emailLayout>
