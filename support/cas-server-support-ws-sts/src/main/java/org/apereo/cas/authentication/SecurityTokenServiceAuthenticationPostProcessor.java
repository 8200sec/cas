package org.apereo.cas.authentication;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.cxf.rt.security.SecurityConstants;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apereo.cas.CipherExecutor;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.services.UnauthorizedSsoServiceException;
import org.apereo.cas.util.EncodingUtils;
import org.apereo.cas.ws.idp.IdentityProviderConfigurationService;
import org.apereo.cas.ws.idp.WSFederationConstants;
import org.apereo.cas.ws.idp.services.WSFederationRegisteredService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

/**
 * This is {@link SecurityTokenServiceAuthenticationPostProcessor}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
public class SecurityTokenServiceAuthenticationPostProcessor implements AuthenticationPostProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityTokenServiceAuthenticationPostProcessor.class);

    private final ServicesManager servicesManager;
    private final IdentityProviderConfigurationService identityProviderConfigurationService;
    private final AuthenticationServiceSelectionStrategy selectionStrategy;
    private final CipherExecutor<String, String> credentialCipherExecutor;
    private final SecurityTokenServiceClientBuilder clientBuilder;

    public SecurityTokenServiceAuthenticationPostProcessor(final ServicesManager servicesManager,
                                                           final IdentityProviderConfigurationService identityProviderConfigurationService,
                                                           final AuthenticationServiceSelectionStrategy selectionStrategy,
                                                           final CipherExecutor<String, String> credentialCipherExecutor, 
                                                           final SecurityTokenServiceClientBuilder clientBuilder) {
        this.servicesManager = servicesManager;
        this.identityProviderConfigurationService = identityProviderConfigurationService;
        this.selectionStrategy = selectionStrategy;
        this.credentialCipherExecutor = credentialCipherExecutor;
        this.clientBuilder = clientBuilder;
    }

    @Override
    public void process(final AuthenticationTransaction transaction, final AuthenticationBuilder builder) {

        if (!this.selectionStrategy.supports(transaction.getService())) {
            return;
        }

        final Service service = this.selectionStrategy.resolveServiceFrom(transaction.getService());
        if (service != null) {
            final WSFederationRegisteredService rp = this.servicesManager.findServiceBy(service, WSFederationRegisteredService.class);
            if (rp == null || !rp.getAccessStrategy().isServiceAccessAllowed()) {
                LOGGER.warn("Service [{}] is not allowed to use SSO.", rp);
                throw new UnauthorizedSsoServiceException();
            }
            
            final SecurityTokenServiceClient sts = clientBuilder.buildClientForSecurityTokenRequests(rp);
            invokeSecurityTokenServiceForToken(transaction, builder, rp, sts);
        }
    }

    private void invokeSecurityTokenServiceForToken(final AuthenticationTransaction transaction, final AuthenticationBuilder builder,
                                                    final WSFederationRegisteredService rp, final SecurityTokenServiceClient sts) {
        final UsernamePasswordCredential up = transaction.getCredentials()
                .stream()
                .filter(UsernamePasswordCredential.class::isInstance)
                .map(UsernamePasswordCredential.class::cast)
                .findFirst()
                .orElse(null);

        if (up != null) {
            try {
                sts.getProperties().put(SecurityConstants.USERNAME, up.getUsername());
                final String uid = credentialCipherExecutor.encode(up.getUsername());
                sts.getProperties().put(SecurityConstants.PASSWORD, uid);
                final SecurityToken token = sts.requestSecurityToken(rp.getAppliesTo());
                final String tokenStr = EncodingUtils.encodeBase64(SerializationUtils.serialize(token));
                builder.addAttribute(WSFederationConstants.SECURITY_TOKEN_ATTRIBUTE, tokenStr);
            } catch (final Exception e) {
                throw new AuthenticationException(e.getMessage());
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
