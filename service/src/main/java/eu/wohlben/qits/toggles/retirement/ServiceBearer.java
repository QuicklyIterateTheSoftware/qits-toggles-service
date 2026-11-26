package eu.wohlben.qits.toggles.retirement;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The bearer this service presents to its peers: its own {@code qits:system} token, minted by the
 * named oidc client {@code qits} (the {@code idp:client} resource, id {@code <env>-qits-toggles}).
 * The mint is cached and refreshed by {@link TokensHelper}, as qits-egress' {@code LeafBearer} does.
 *
 * <p>Off ({@code quarkus.oidc-client.qits.client-enabled}, false under %dev and %test) it answers
 * empty without dialling anything. Blocking: the retirement run is a worker-thread call.
 */
@ApplicationScoped
public class ServiceBearer {

  static final Duration MINT_TIMEOUT = Duration.ofSeconds(15);

  @Inject
  @NamedOidcClient("qits")
  OidcClient qits;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled", defaultValue = "false")
  boolean enabled;

  private final TokensHelper helper = new TokensHelper();

  /** The access token, or empty when this process holds no client. */
  public Optional<String> token() {
    if (!enabled) {
      return Optional.empty();
    }
    return Optional.ofNullable(helper.getTokens(qits).await().atMost(MINT_TIMEOUT).getAccessToken())
        .filter(token -> !token.isBlank());
  }
}
