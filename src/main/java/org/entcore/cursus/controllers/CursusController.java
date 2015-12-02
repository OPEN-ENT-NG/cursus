package org.entcore.cursus.controllers;

import java.net.URL;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.cursus.filters.CursusFilter;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.http.HttpClientRequest;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpHeaders;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.RouteMatcher;
import org.vertx.java.core.impl.VertxInternal;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.shareddata.ConcurrentSharedMap;
import org.vertx.java.core.spi.cluster.ClusterManager;
import org.vertx.java.platform.Container;

import fr.wseduc.rs.*;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.http.BaseController;

public class CursusController extends BaseController {

	//Service
	private final CursusService service = new CursusService();

	//Webservice client & endpoint
	private final HttpClient cursusClient;
	private final URL wsEndpoint;

	//Webservice auth request conf
	private final JsonObject authConf;

	//Auth reply data
	private Map<String, String> authMap;
	//Wallets list
	private JsonArray wallets = new JsonArray();

	@Override
	public void init(Vertx vertx, final Container container, RouteMatcher rm,
			Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
		super.init(vertx, container, rm, securedActions);

		ConcurrentSharedMap<Object, Object> server = vertx.sharedData().getMap("server");
		Boolean cluster = (Boolean) server.get("cluster");
		if (Boolean.TRUE.equals(cluster)) {
			ClusterManager cm = ((VertxInternal) vertx).clusterManager();
			authMap = cm.getSyncMap("cursusMap");
		} else {
			authMap = new HashMap<>();
		}

		/*
		service.refreshToken(new Handler<Boolean>() {
			public void handle(Boolean res) {
				if(!res)
					log.error("[Cursus][refreshToken] Error while retrieving the Token.");
				else
					log.info("[Cursus][refreshToken] Token refreshed.");
			}
		});
		*/
		service.refreshWallets(new Handler<Boolean>() {
			public void handle(Boolean res) {
				if(!res)
					log.error("[Cursus][refreshWallets] Error while retrieving the wallets list.");
				else
					log.info("[Cursus][refreshWallets] Wallets list refreshed.");
			}
		});

	}

	public CursusController(HttpClient webClient, URL endpoint, final JsonObject conf){
		cursusClient = webClient;
		cursusClient.setHost(endpoint.getHost());

		if("https".equals(endpoint.getProtocol())){
			cursusClient
				.setSSL(true)
				.setTrustAll(true)
				.setPort(443);
		} else {
			cursusClient
				.setPort(endpoint.getPort() == -1 ? 80 : endpoint.getPort());
		}

		wsEndpoint = endpoint;
		authConf = conf;
	}

	@Put("/refreshToken")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(CursusFilter.class)
	public void refreshToken(final HttpServerRequest request){
		service.refreshToken(new Handler<Boolean>() {
			public void handle(Boolean success) {
				if(success){
					ok(request);
				} else {
					badRequest(request);
				}
			}
		});

	}

	@Put("/refreshWallets")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(CursusFilter.class)
	public void refreshWallets(final HttpServerRequest request){
		service.refreshWallets(new Handler<Boolean>() {
			public void handle(Boolean success) {
				if(success){
					ok(request);
				} else {
					badRequest(request);
				}
			}
		});
	}

	@Get("/sales")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void getSales(final HttpServerRequest request){
		final String cardNb = request.params().get("cardNb");
		if(cardNb == null){
			badRequest(request);
			return;
		}

		service.getUserInfo(cardNb, new Handler<Either<String,JsonArray>>() {
			public void handle(Either<String, JsonArray> result) {
				if(result.isLeft()){
					badRequest(request);
					return;
				}

				final String id = ((JsonObject) result.right().getValue().get(0)).getInteger("id").toString();

				service.getSales(id, cardNb, new Handler<Either<String,JsonArray>>() {
					public void handle(Either<String, JsonArray> result) {
						if(result.isLeft()){
							badRequest(request);
							return;
						}

						JsonObject finalResult = new JsonObject()
							.putArray("wallets", wallets)
							.putArray("sales", result.right().getValue());

						renderJson(request, finalResult);
					}
				});
			}
		});
	}

	/**
	 * Inner service class.
	 */
	private class CursusService{

		public void authWrapper(final Handler<Boolean> handler){
			JsonObject authObject = new JsonObject();
			if(authMap.get("auth") != null)
				authObject = new JsonObject(authMap.get("auth"));

			Long currentDate = Calendar.getInstance().getTimeInMillis();
			Long expirationDate = 0l;
			if(authObject != null)
				expirationDate = authObject.getLong("tokenInit", 0l) + authConf.getLong("tokenDelay", 1800000l);

			if(expirationDate < currentDate){
				log.info("[Cursus] Token seems to have expired.");
				refreshToken(handler);
			} else {
				handler.handle(true);
			}
		}

		public void refreshToken(final Handler<Boolean> handler){
			HttpClientRequest req = cursusClient.post(wsEndpoint.getPath() + "/AuthentificationImpl.svc/json/AuthentificationExtranet", new Handler<HttpClientResponse>() {
				public void handle(HttpClientResponse response) {
					if(response.statusCode() >= 300){
						handler.handle(false);
						log.error(response.statusMessage());
						return;
					}

					response.bodyHandler(new Handler<Buffer>() {
						public void handle(Buffer body) {
							log.info("[Cursus][refreshToken] Token refreshed.");

							JsonObject authData = new JsonObject(body.toString());
							authData.putNumber("tokenInit", new Date().getTime());
							authMap.put("auth", authData.encode());
							handler.handle(true);
						}
					});
				}
			});
			req.putHeader(HttpHeaders.ACCEPT, "application/json; charset=UTF-8")
			   .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
			req.end(authConf.encode());
		}

		public void refreshWallets(final Handler<Boolean> handler){
			authWrapper(new Handler<Boolean>() {
				public void handle(Boolean gotToken) {
					if(!gotToken){
						handler.handle(false);
						return;
					}

					int schoolYear = Calendar.getInstance().get(Calendar.MONTH) < 8 ?
							Calendar.getInstance().get(Calendar.YEAR) - 1 :
							Calendar.getInstance().get(Calendar.YEAR);

					/* JSON */
					JsonObject reqBody = new JsonObject();
					reqBody
						.putString("numSite", authConf.getString("numSite"))
						.putString("tokenId", new JsonObject(authMap.get("auth")).getString("tokenId"))
						.putArray("typeListes", new JsonArray()
							.addObject(new JsonObject()
								.putString("typeListe", "LST_PORTEMONNAIE")
								.putString("param1", schoolYear + "-" + (schoolYear + 1))
							)
						);
					/*      */

					/* XML /
					String reqBody =
						"<tem:GetListes xmlns:tem=\"http://tempuri.org/\" xmlns:wcf=\"http://schemas.datacontract.org/2004/07/WcfExtranetChequeBL.POCO.Parametres\">" +
							"<tem:numSite>"+ authConf.getString("numSite") +"</tem:numSite>" +
							"<tem:typeListes>" +
								"<wcf:RechercheTypeListe>" +
									"<wcf:typeListe>LST_PORTEMONNAIE</wcf:typeListe>" +
									"<wcf:param1>"+ schoolYear + "-" + (schoolYear + 1) +"</wcf:param1>" +
								"</wcf:RechercheTypeListe>" +
							"</tem:typeListes>" +
							"<tem:tokenId>"+ authData.getString("tokenId") +"</tem:tokenId>" +
						"</tem:GetListes>";
					/*      */

					HttpClientRequest req = cursusClient.post(wsEndpoint.getPath() + "/GeneralImpl.svc/json/GetListes", new Handler<HttpClientResponse>() {
						public void handle(HttpClientResponse response) {
							if(response.statusCode() >= 300){
								handler.handle(false);
								log.error(response.statusMessage());
								return;
							}

							response.bodyHandler(new Handler<Buffer>() {
								public void handle(Buffer body) {
									try{
										wallets = ((JsonObject) new JsonArray(body.toString()).get(0)).getArray("parametres");
										handler.handle(true);
									} catch(Exception e){
										handler.handle(false);
									}
								}
							});
						}
					});
					req.putHeader(HttpHeaders.ACCEPT, "application/json; charset=UTF-8")
					   .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
					req.end(reqBody.encode());
				}
			});
		};

		public void getUserInfo(final String cardNb, final Handler<Either<String, JsonArray>> handler){
			authWrapper(new Handler<Boolean>() {
				public void handle(Boolean gotToken) {
					if(!gotToken){
						handler.handle(new Either.Left<String, JsonArray>("[Cursus][getUserInfo] Issue while retrieving token."));
						return;
					}

					JsonObject reqBody = new JsonObject();
					reqBody
						.putString("numSite", authConf.getString("numSite"))
						.putString("tokenId", new JsonObject(authMap.get("auth")).getString("tokenId"))
						.putObject("filtres", new JsonObject()
							.putString("numeroCarte", cardNb));

					HttpClientRequest req = cursusClient.post(wsEndpoint.getPath() + "/BeneficiaireImpl.svc/json/GetListeBeneficiaire", new Handler<HttpClientResponse>() {
						public void handle(HttpClientResponse response) {
							if(response.statusCode() >= 300){
								handler.handle(new Either.Left<String, JsonArray>("invalid.status.code"));
								return;
							}

							response.bodyHandler(new Handler<Buffer>() {
								public void handle(Buffer body) {
									handler.handle(new Either.Right<String, JsonArray>(new JsonArray(body.toString())));
								}
							});
						}
					});
					req.putHeader(HttpHeaders.ACCEPT, "application/json; charset=UTF-8")
					   .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
					req.end(reqBody.encode());
				}
			});
		}

		public void getSales(final String numeroDossier, final String cardNb, final Handler<Either<String, JsonArray>> handler){
			authWrapper(new Handler<Boolean>() {
				public void handle(Boolean gotToken) {
					if(!gotToken){
						handler.handle(new Either.Left<String, JsonArray>("[Cursus][getSales] Issue while retrieving token."));
						return;
					}

					JsonObject reqBody = new JsonObject();
					reqBody
						.putString("numeroSite", authConf.getString("numSite"))
						.putString("tokenId", new JsonObject(authMap.get("auth")).getString("tokenId"))
						.putObject("filtresSoldesBeneficiaire", new JsonObject()
							.putString("numeroDossier", numeroDossier)
							.putString("numeroCarte", cardNb));

					HttpClientRequest req = cursusClient.post(wsEndpoint.getPath() + "/BeneficiaireImpl.svc/json/GetSoldesBeneficiaire", new Handler<HttpClientResponse>() {
						public void handle(HttpClientResponse response) {
							if(response.statusCode() >= 300){
								handler.handle(new Either.Left<String, JsonArray>("invalid.status.code"));
								return;
							}

							response.bodyHandler(new Handler<Buffer>() {
								public void handle(Buffer body) {
									handler.handle(new Either.Right<String, JsonArray>(new JsonArray(body.toString())));
								}
							});
						}
					});
					req.putHeader(HttpHeaders.ACCEPT, "application/json; charset=UTF-8")
					   .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
					req.end(reqBody.encode());
				}
			});
		}

	}
}
