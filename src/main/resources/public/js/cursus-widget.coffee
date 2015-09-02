cursusWidget = model.widgets.findWidget "cursus"

cursusWidget.model =
	loadingWrapper: (http) ->
		if not http or not http.xhr?.always
			return
		http.xhr?.always () =>
			@loads = false
			model.widgets.apply()
		@loads = true
		model.widgets.apply()

	logout: () ->
		delete @cardNb
		delete @sales
		http().putJson("/userbook/preference/cursus", {})

	connect: (cardNb) ->
		@cardNb = cardNb
		@loadingWrapper @getSales()

	getCardNb: (hook) ->
		http().get("/userbook/preference/cursus", {})
			.done (data) =>
				@cardNb = (JSON.parse data.preference)?.cardNb
				hook?.bind?(@)?()
				model.widgets.apply()


	setCardNb: (cardNb) ->
		http().putJson("/userbook/preference/cursus", { cardNb: cardNb })

	getSales: () ->
		if not @cardNb
			return
		http().get("/cursus/sales", { cardNb: @cardNb })
			.done (data) =>
				@error = false
				@setCardNb(@cardNb)
				@sales = data.sales
				_.forEach @sales, (sale) ->
					sale.wallet = _.findWhere(data.wallets, { code: sale.numPM })
				model.widgets.apply()
			.error =>
				@error = true
				model.widgets.apply()


### INIT ###
(() ->
	m = cursusWidget.model
	m.getCardNb () ->
		@loadingWrapper @getSales()
)()
