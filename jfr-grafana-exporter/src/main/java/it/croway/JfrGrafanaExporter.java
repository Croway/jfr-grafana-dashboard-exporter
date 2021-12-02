package it.croway;

import org.apache.http.client.fluent.Request;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.util.EntityUtils;

import org.jetbrains.annotations.NotNull;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.item.ItemToolkit;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import picocli.CommandLine;

@CommandLine.Command(name = "JfrGrafanaExporter", mixinStandardHelpOptions = true, version = "0.0.1",
		description = "JfrGrafanaExporter made with jbang")
public class JfrGrafanaExporter implements Callable<Integer> {
	static final Logger LOGGER = LoggerFactory.getLogger(JfrGrafanaExporter.class);

	static final Network network = Network.newNetwork();

	static final GenericContainer<?> jfrDatasourceContainer = new GenericContainer<>("croway/jfr-datasource:2.1.0")
			.withExposedPorts(8080)
			.withNetwork(network)
			.withNetworkAliases("jfr-datasource");

	static final GenericContainer<?> grafanaContainer = new GenericContainer<>("grafana/grafana")
			.withExposedPorts(3000)
			.withNetwork(network)
			.withNetworkAliases("grafana")
			.withEnv("GF_INSTALL_PLUGINS", "grafana-simple-json-datasource")
			.withEnv("GF_RENDERING_SERVER_URL", "http://grafana-image-renderer:8081/render")
			.withEnv("GF_RENDERING_CALLBACK_URL", "http://grafana:3000")
			.withEnv("GF_LOG_FILTERS", "rendering:debug")
			.withClasspathResourceMapping("provisioning", "/etc/grafana/provisioning", BindMode.READ_WRITE)
			.withClasspathResourceMapping("dashboards", "/var/lib/grafana/dashboards", BindMode.READ_WRITE);

	static final GenericContainer<?> grafanaRendererContainer = new GenericContainer<>("grafana/grafana-image-renderer")
			.withExposedPorts(8081)
			.withNetwork(network)
			.withNetworkAliases("grafana-image-renderer");

	long startTime = Long.MAX_VALUE;
	long endTime = Long.MIN_VALUE;
	ObjectMapper mapper = new ObjectMapper();

	@CommandLine.Parameters(index = "0", description = "JFR File path")
	private String jfrPath;
	@CommandLine.Parameters(index = "1", description = "Reports path", defaultValue = "reports")
	private String reportsPath;

	public static void main(String... args) {
		int exitCode = new CommandLine(new JfrGrafanaExporter()).execute("/home/federico/Work/croway/camel-performance-tests/profiling/timer-log/camel-recording17404718817072294048.jfr");
		System.exit(exitCode);
	}

	private static String basicAuth(String username, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
	}

	@Override
	public Integer call() throws Exception {
		try {
			startContainers();

			if (jfrPath == null) {
				throw new IllegalArgumentException("index 0, jfr recording path is required");
			}

			File jrfRecordFile = new File(jfrPath);

			computeStartEndTimeFromJfrRecord(jrfRecordFile);

			createExportDirectory();

			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(20))
					.build();

			String uid = getGrafanaDashboardUid("JFR Events", client);

			uploadJfrToJfrDatasource(jrfRecordFile);

			// Download panels
			downloadPanels(client, uid);
		} catch (Exception ex) {
			LOGGER.error(ex.getMessage(), ex);
			return 1;
		} finally {
			jfrDatasourceContainer.stop();
			grafanaContainer.stop();
			grafanaRendererContainer.stop();
			network.close();
		}

		return 0;
	}

	private void downloadPanels(HttpClient client, String uid) throws IOException, InterruptedException {
		JsonNode node = mapper.readValue(JfrGrafanaExporter.class.getResourceAsStream("/dashboards/camel-jfr.json"), JsonNode.class);
		List<JsonNode> nodes = StreamSupport
				.stream(node.get("panels").spliterator(), false)
				.collect(Collectors.toList());

		nodes.parallelStream().forEach(panel -> {
			try {
				URI uri = URI.create("http://localhost:" + grafanaContainer.getMappedPort(3000) + "/render/d-solo/" + uid + "/jfr-events?orgId=1&from=" + startTime + "&to=" + endTime + "&panelId=" + panel.get("id").asInt() + "&width=1000&height=500&tz=Europe/Rome");
				HttpRequest request = HttpRequest.newBuilder()
						.uri(uri)
						.GET()
						.header("Authorization", basicAuth("admin", "admin"))
						.build();

				HttpResponse<InputStream> responseIS = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

				LOGGER.info("png \"{}\" download response status {}", panel.get("title").asText(), responseIS.statusCode());
				try (FileOutputStream out = new FileOutputStream(reportsPath + File.separator + panel.get("title").asText() + ".png")) {
					responseIS.body().transferTo(out);
				}
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		});
	}

	private void uploadJfrToJfrDatasource(File jrfRecordFile) throws IOException {
		org.apache.http.HttpResponse returnResponse = Request.Post("http://localhost:" + jfrDatasourceContainer.getMappedPort(8080) + "/load")
				.body(MultipartEntityBuilder.create().addPart("file", new FileBody(jrfRecordFile)).build())
				.execute().returnResponse();

		LOGGER.info("jfrDatasourceResponse\n{}", EntityUtils.toString(returnResponse.getEntity()));
		LOGGER.info("jfrDatasourceResponseCode {}", returnResponse.getStatusLine().getStatusCode());
	}

	private String getGrafanaDashboardUid(String jfr_events, HttpClient client) throws Exception {
		String uid = null;

		HttpRequest grafanaRequest = HttpRequest
				.newBuilder(new URI("http://localhost:" + grafanaContainer.getMappedPort(3000) + "/api/search?folderIds=0&query=&starred=false"))
				.header("Authorization", basicAuth("admin", "admin"))
				.GET().build();

		HttpResponse<String> response = client.send(grafanaRequest, HttpResponse.BodyHandlers.ofString());

		JsonNode jsonNode = mapper.readValue(response.body(), JsonNode.class);
		for (JsonNode dashboardNode : jsonNode) {
			if ("JFR Events".equals(dashboardNode.get("title").asText())) {
				uid = dashboardNode.get("uid").asText();
			}
		}

		LOGGER.info("Dashboard uid {}", uid);
		LOGGER.info("jfrPath {}", jfrPath);

		return uid;
	}

	private void createExportDirectory() {
		File reports = new File(reportsPath);
		if (!reports.exists()) {
			reports.mkdirs();
		}
		LOGGER.info("reports path {}", reports.getAbsolutePath());
	}

	@NotNull
	private File computeStartEndTimeFromJfrRecord(File file) throws IOException, CouldNotLoadRecordingException {
		IItemCollection itemCollection = JfrLoaderToolkit.loadEvents(file);

		Iterator<IItemIterable> i = itemCollection.iterator();
		while (i.hasNext()) {

			IItemIterable item = i.next();

			if (item.hasItems()) {
				IType<IItem> type = item.getType();
				List<IAttribute<?>> attributes = type.getAttributes();
				for (IAttribute<?> attribute : attributes) {
					if (attribute.getIdentifier().contains("startTime")) {
						IMemberAccessor<IQuantity, IItem> startTimeAccessor = JfrAttributes.START_TIME.getAccessor(type);
						IMemberAccessor<?, IItem> accessor = ItemToolkit.accessor(attribute);

						for (IItem it : item) {
							try {
								if (startTime > startTimeAccessor.getMember(it).longValueIn(UnitLookup.EPOCH_MS)) {
									startTime = startTimeAccessor.getMember(it).longValueIn(UnitLookup.EPOCH_MS);
								}
							} catch (QuantityConversionException e) {
								// Do Nothing
							}
						}
					}
					if (attribute.getIdentifier().contains("endTime")) {
						IMemberAccessor<IQuantity, IItem> endTimeAccessor = JfrAttributes.END_TIME.getAccessor(type);
						IMemberAccessor<?, IItem> accessor = ItemToolkit.accessor(attribute);

						for (IItem it : item) {
							try {
								if (endTime < endTimeAccessor.getMember(it).longValueIn(UnitLookup.EPOCH_MS)) {
									endTime = endTimeAccessor.getMember(it).longValueIn(UnitLookup.EPOCH_MS);
								}
							} catch (QuantityConversionException e) {
								// Do Nothing
							}
						}
					}
				}
			}
		}
		return file;
	}

	private void startContainers() {
		LOGGER.debug("network id {}", network.getId());
		jfrDatasourceContainer.start();
		grafanaRendererContainer.start();
		grafanaContainer.start();
	}
}
