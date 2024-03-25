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
import java.util.Map;

import io.vertx.core.http.*;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.MapFactory;
import org.entcore.cursus.filters.CursusFilter;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.vertx.java.core.http.RouteMatcher;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;


import fr.wseduc.rs.*;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.http.BaseController;

public class CursusController extends BaseController {

	//Service
	private final CursusService service = new CursusService();

	//Webservice client & endpoint
	private HttpClient cursusClient;
	private final URL wsEndpoint;

	//Webservice auth request conf
	private final JsonObject authConf;

	//Auth reply data & wallets list
	private Map<String, String> cursusMap;

	@Override
	public void init(Vertx vertx, JsonObject config, RouteMatcher rm,
			Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
		super.init(vertx, config, rm, securedActions);

		HttpClientOptions cursusClientOptions = new HttpClientOptions()
				.setDefaultHost(wsEndpoint.getHost());

		if("https".equals(wsEndpoint.getProtocol())){
			cursusClientOptions
					.setSsl(true)
					.setTrustAll(true)
					.setDefaultPort(443);
		} else {
			cursusClientOptions
					.setDefaultPort(wsEndpoint.getPort() == -1 ? 80 : wsEndpoint.getPort());
		}
		cursusClient = vertx.createHttpClient(cursusClientOptions);

		cursusMap = MapFactory.getSyncClusterMap("cursusMap", vertx, false);

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

	public CursusController(URL endpoint, final JsonObject conf){
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

				final String id = result.right().getValue().getJsonObject(0).getInteger("id").toString();
				String birthDateEncoded = result.right().getValue().getJsonObject(0).getString("dateNaissance");
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
												.put("wallets", new JsonArray(cursusMap.get("wallets")))
												.put("sales", result.right().getValue());

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
			cursusClient.request(new RequestOptions()
					.setMethod(HttpMethod.POST)
					.setURI(wsEndpoint.getPath() + "/AuthentificationImpl.svc/json/AuthentificationExtranet")
					.setHeaders(new HeadersMultiMap()
							.add(HttpHeaders.ACCEPT, "application/json; charset=UTF-8")
							.add(HttpHeaders.CONTENT_TYPE, "application/json")))
					.flatMap(request -> request.send(authConf.encode()))
					.onSuccess(response -> {
						if(response.statusCode() >= 300){
							handler.handle(false);
							log.error(response.statusMessage());
							return;
						}

						response.bodyHandler(new Handler<Buffer>() {
							public void handle(Buffer body) {
								log.info("[Cursus][refreshToken] Token refreshed.");

								JsonObject authData = new JsonObject(body.toString());
								authData.put("tokenInit", new Date().getTime());
								cursusMap.put("auth", authData.encode());
								handler.handle(true);
							}
						});
					});
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
						.put("numSite", authConf.getString("numSite"))
						.put("tokenId", new JsonObject(cursusMap.get("auth")).getString("tokenId"))
						.put("typeListes", new JsonArray()
							.add(new JsonObject()
								.put("typeListe", "LST_PORTEMONNAIE")
								.put("param1", schoolYear + "-" + (schoolYear + 1))
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

					cursusClient.request(new RequestOptions()
							.setMethod(HttpMethod.POST)
							.setURI(wsEndpoint.getPath() + "/GeneralImpl.svc/json/GetListes")
							.setHeaders(new HeadersMultiMap()
									.add(HttpHeaders.ACCEPT, "application/json; charset=UTF-8")
									.add(HttpHeaders.CONTENT_TYPE, "application/json")))
							.flatMap(request -> request.send(reqBody.encode()))
							.onSuccess(response -> {
								if(response.statusCode() >= 300){
									handler.handle(false);
									log.error(response.statusMessage());
									return;
								}

								response.bodyHandler(new Handler<Buffer>() {
									public void handle(Buffer body) {
										try{
											cursusMap.put("wallets", new JsonArray(body.toString()).getJsonObject(0)
													.getJsonArray("parametres").encode());
											handler.handle(true);
										} catch(Exception e){
											handler.handle(false);
										}
									}
								});
							});
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
						.put("numSite", authConf.getString("numSite"))
						.put("tokenId", new JsonObject(cursusMap.get("auth")).getString("tokenId"))
						.put("filtres", new JsonObject()
							.put("numeroCarte", cardNb));

					cursusClient.request(new RequestOptions()
							.setMethod(HttpMethod.POST)
							.setURI(wsEndpoint.getPath() + "/BeneficiaireImpl.svc/json/GetListeBeneficiaire")
							.setHeaders(new HeadersMultiMap()
									.add(HttpHeaders.ACCEPT, "application/json; charset=UTF-8")
									.add(HttpHeaders.CONTENT_TYPE, "application/json")))
							.flatMap(request -> request.send(reqBody.encode()))
							.onSuccess(response -> {
								if(response.statusCode() >= 300){
									handler.handle(new Either.Left<String, JsonArray>("invalid.status.code"));
									return;
								}

								response.bodyHandler(new Handler<Buffer>() {
									public void handle(Buffer body) {
										handler.handle(new Either.Right<String, JsonArray>(new JsonArray(body.toString())));
									}
								});
							});
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
						.put("numeroSite", authConf.getString("numSite"))
						.put("tokenId", new JsonObject(cursusMap.get("auth")).getString("tokenId"))
						.put("filtresSoldesBeneficiaire", new JsonObject()
							.put("numeroDossier", numeroDossier)
							.put("numeroCarte", cardNb));

					cursusClient.request(new RequestOptions()
							.setMethod(HttpMethod.POST)
							.setURI(wsEndpoint.getPath() + "/BeneficiaireImpl.svc/json/GetSoldesBeneficiaire")
							.setHeaders(new HeadersMultiMap()
									.add(HttpHeaders.ACCEPT, "application/json; charset=UTF-8")
									.add(HttpHeaders.CONTENT_TYPE, "application/json")))
							.flatMap(request -> request.send(reqBody.encode()))
							.onSuccess(response -> {
								if(response.statusCode() >= 300){
									handler.handle(new Either.Left<String, JsonArray>("invalid.status.code"));
									return;
								}

								response.bodyHandler(new Handler<Buffer>() {
									public void handle(Buffer body) {
										handler.handle(new Either.Right<String, JsonArray>(new JsonArray(body.toString())));
									}
								});
							});
				}
			});
		}

	}
}
