///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.croway:jfr-grafana-exporter:1.0

import it.croway.JfrGrafanaExporter;

public class JfrGrafanaDashboardExporter {
	public static void main(String[] args) {
		JfrGrafanaExporter.main(args);
	}
}
