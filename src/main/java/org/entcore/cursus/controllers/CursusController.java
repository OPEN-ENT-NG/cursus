/*
 * Copyright © Région Nord Pas de Calais-Picardie.
 *
 * This file is part of OPEN ENT NG. OPEN ENT NG is a versatile ENT Project based on the JVM and ENT Core Project.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with OPEN ENT NG is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of OPEN ENT NG, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.entcore.cursus.controllers;

import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
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

	//Auth reply data & wallets list
	private Map<String, String> cursusMap;

	@Override
	public void init(Vertx vertx, final Container container, RouteMatcher rm,
			Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
		super.init(vertx, container, rm, securedActions);

		ConcurrentSharedMap<Object, Object> server = vertx.sharedData().getMap("server");
		Boolean cluster = (Boolean) server.get("cluster");
		if (Boolean.TRUE.equals(cluster)) {
			ClusterManager cm = ((VertxInternal) vertx).clusterManager();
			cursusMap = cm.getSyncMap("cursusMap");
		} else {
			cursusMap = new HashMap<>();
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
		if(cursusMap.containsKey("wallets"))
			return;
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
				String birthDateEncoded = ((JsonObject) result.right().getValue().get(0)).getString("dateNaissance");
				try {
					birthDateEncoded = birthDateEncoded.replace("/Date(", "");
					birthDateEncoded = birthDateEncoded.substring(0, birthDateEncoded.indexOf("+"));
					final Date birthDate = new Date(Long.parseLong(birthDateEncoded));

					UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
						public void handle(UserInfos infos) {
							DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
							try {
								Date sessionBirthDate = format.parse(infos.getBirthDate());
								if(sessionBirthDate.compareTo(birthDate) == 0){
									service.getSales(id, cardNb, new Handler<Either<String,JsonArray>>() {
										public void handle(Either<String, JsonArray> result) {
											if(result.isLeft()){
												badRequest(request);
												return;
											}

											JsonObject finalResult = new JsonObject()
												.putArray("wallets", new JsonArray(cursusMap.get("wallets")))
												.putArray("sales", result.right().getValue());

											renderJson(request, finalResult);
										}
									});
								} else {
									badRequest(request);
								}
							} catch (ParseException e) {
								badRequest(request);
								return;
							}
						}
					});
				} catch(Exception e){
					badRequest(request);
				}
			}
		});
	}

	/**
	 * Inner service class.
	 */
	private class CursusService{

		public void authWrapper(final Handler<Boolean> handler){
			JsonObject authObject = new JsonObject();
			if(cursusMap.get("auth") != null)
				authObject = new JsonObject(cursusMap.get("auth"));

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
							cursusMap.put("auth", authData.encode());
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
						.putString("tokenId", new JsonObject(cursusMap.get("auth")).getString("tokenId"))
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
										cursusMap.put("wallets", ((JsonObject) new JsonArray(body.toString()).get(0)).getArray("parametres").encode());
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
						.putString("tokenId", new JsonObject(cursusMap.get("auth")).getString("tokenId"))
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
						.putString("tokenId", new JsonObject(cursusMap.get("auth")).getString("tokenId"))
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
