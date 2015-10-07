package org.entcore.cursus;

import java.net.MalformedURLException;
import java.net.URL;

import org.entcore.common.http.BaseServer;
import org.entcore.cursus.controllers.CursusController;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.json.JsonObject;

public class Cursus extends BaseServer {

	private HttpClient cursusClient;


	@Override
	public void start() {
		super.start();

		final String endpoint = container.config().getString("webserviceEndpoint", "");
		final JsonObject conf = container.config().getObject("authConf", new JsonObject());
		cursusClient = vertx.createHttpClient();

		URL endpointURL;
		try {
			endpointURL = new URL(endpoint);
			addController(new CursusController(cursusClient, endpointURL, conf));
		} catch (MalformedURLException e) {
			log.error("[Cursus] Bad endpoint url.");
		}
	}

	@Override
	public void stop(){
		super.stop();
		cursusClient.close();
	}

}
