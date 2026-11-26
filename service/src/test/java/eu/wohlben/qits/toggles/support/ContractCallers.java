package eu.wohlben.qits.toggles.support;

import io.quarkus.arc.Arc;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>The callers a suite names by their {@code Authorization} value</b> on the one listener
 * (8080): the {@code %test} suite runs with the OIDC tenant off, so an {@code Authorization} header
 * means nothing until it is granted here. qits-agents-service's {@code ContractCallers}, with the
 * {@code groups} claim a caller check reads.
 *
 * <ul>
 *   <li>{@link #caller} — a named caller holding roles, as a plain principal (no token behind it);
 *       its scheme is {@code Suite}, never {@code Bearer}.
 *   <li>{@link #bearer} — a granted bearer value, which becomes the identity quarkus-oidc builds
 *       for such a token: a JWT principal with its {@code sub}, its {@code groups} and its roles.
 *   <li>{@link #client} — a platform service's own client token: {@code sub} is the client id,
 *       {@code groups} hold {@code clients/<sub>} and {@code qits:system}, and so does the role set.
 * </ul>
 */
@ApplicationScoped
public class ContractCallers implements HttpAuthenticationMechanism {

  static final String SCHEME = "Suite ";

  static final String PLATFORM_AUDIENCE = "qits-platform";

  record Grant(String subject, Set<String> groups, Set<String> roles, boolean token) {}

  private final Map<String, Grant> grants = new ConcurrentHashMap<>();

  /** The {@code Authorization} value of {@code subject} holding {@code roles}, no token. */
  public static String caller(String subject, String... roles) {
    String authorization = SCHEME + subject + "." + String.join("+", roles);
    bean().grant(authorization, new Grant(subject, Set.of(), Set.of(roles), false));
    return authorization;
  }

  /** A request with {@code Authorization: <authorization>} is this token. */
  public static void bearer(
      String authorization, String subject, Set<String> groups, Set<String> roles) {
    bean().grant(authorization, new Grant(subject, Set.copyOf(groups), Set.copyOf(roles), true));
  }

  /** The {@code Authorization} value of the platform service client {@code clientId}. */
  public static String client(String clientId) {
    String authorization = "Bearer suite-client-" + clientId;
    bearer(
        authorization,
        clientId,
        Set.of("clients/" + clientId, "qits:system"),
        Set.of("clients/" + clientId, "qits:system"));
    return authorization;
  }

  private static ContractCallers bean() {
    return Arc.container().instance(ContractCallers.class).get();
  }

  void grant(String authorization, Grant grant) {
    grants.put(authorization, grant);
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String authorization = context.request().getHeader("Authorization");
    Grant grant = authorization == null ? null : grants.get(authorization);
    if (grant == null) {
      return Uni.createFrom().optional(Optional.empty());
    }
    QuarkusSecurityIdentity.Builder identity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(
                grant.token()
                    ? new Token(grant.subject(), grant.groups())
                    : new QuarkusPrincipal(grant.subject()));
    grant.roles().forEach(identity::addRole);
    return Uni.createFrom().item(identity.build());
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom().optional(Optional.empty());
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of();
  }

  @Override
  public int getPriority() {
    return DEFAULT_PRIORITY + 1000;
  }

  /** The JWT quarkus-oidc would build: a subject, its groups and the platform audience. */
  private record Token(String subject, Set<String> groups) implements JsonWebToken {

    @Override
    public String getName() {
      return subject;
    }

    @Override
    public String getSubject() {
      return subject;
    }

    @Override
    public Set<String> getGroups() {
      return groups;
    }

    @Override
    public Set<String> getAudience() {
      return Set.of(PLATFORM_AUDIENCE);
    }

    @Override
    public Set<String> getClaimNames() {
      return Set.of("sub", "aud", "groups");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
      return switch (claimName) {
        case "sub" -> (T) subject;
        case "aud" -> (T) Set.of(PLATFORM_AUDIENCE);
        case "groups" -> (T) groups;
        default -> null;
      };
    }
  }
}
