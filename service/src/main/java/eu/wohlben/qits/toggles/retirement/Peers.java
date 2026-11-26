package eu.wohlben.qits.toggles.retirement;

import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.http.HttpClient;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The two peers the retirement run calls, each at its edge name with this service's own bearer:
 * qits-maintenance ({@code qits.toggles.maintenance-url}) for the deployed pacts, qits-projects
 * ({@code qits.toggles.projects-url}) for the removal tickets. One HTTP client, wrapped by {@link
 * QitsHttpClients} so each call carries the trace, the caller and the chain.
 */
@ApplicationScoped
public class Peers {

  @Inject QitsHttpClients clients;

  @Inject ServiceBearer bearer;

  @ConfigProperty(name = "qits.toggles.maintenance-url")
  String maintenanceUrl;

  @ConfigProperty(name = "qits.toggles.projects-url")
  String projectsUrl;

  private PeerHttp maintenance;
  private PeerHttp projects;

  @PostConstruct
  void connect() {
    HttpClient client =
        clients.wrap(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    maintenance = new PeerHttp(client, maintenanceUrl, bearer::token);
    projects = new PeerHttp(client, projectsUrl, bearer::token);
  }

  public PeerHttp maintenance() {
    return maintenance;
  }

  public PeerHttp projects() {
    return projects;
  }
}
