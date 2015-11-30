var cursusWidget;

cursusWidget = model.widgets.findWidget("cursus-widget");

cursusWidget.model = {
  loadingWrapper: function(http) {
    var ref, ref1;
    if (!http || !((ref = http.xhr) != null ? ref.always : void 0)) {
      return;
    }
    if ((ref1 = http.xhr) != null) {
      ref1.always((function(_this) {
        return function() {
          _this.loads = false;
          return model.widgets.apply();
        };
      })(this));
    }
    this.loads = true;
    return model.widgets.apply();
  },
  logout: function() {
    delete this.cardNb;
    delete this.sales;
    return http().putJson("/userbook/preference/cursus", {});
  },
  connect: function(cardNb) {
    this.cardNb = cardNb;
    return this.loadingWrapper(this.getSales());
  },
  getCardNb: function(hook) {
    return http().get("/userbook/preference/cursus", {}).done((function(_this) {
      return function(data) {
        var base, ref;
        _this.cardNb = (ref = JSON.parse(data.preference)) != null ? ref.cardNb : void 0;
        if (hook != null) {
          if (typeof hook.bind === "function") {
            if (typeof (base = hook.bind(_this)) === "function") {
              base();
            }
          }
        }
        return model.widgets.apply();
      };
    })(this));
  },
  setCardNb: function(cardNb) {
    return http().putJson("/userbook/preference/cursus", {
      cardNb: cardNb
    });
  },
  getSales: function() {
    if (!this.cardNb) {
      return;
    }
    return http().get("/cursus/sales", {
      cardNb: this.cardNb
    }).done((function(_this) {
      return function(data) {
        _this.error = false;
        _this.setCardNb(_this.cardNb);
        _this.sales = data.sales;
        _.forEach(_this.sales, function(sale) {
          return sale.wallet = _.findWhere(data.wallets, {
            code: sale.numPM
          });
        });
        return model.widgets.apply();
      };
    })(this)).error((function(_this) {
      return function() {
        _this.error = true;
        return model.widgets.apply();
      };
    })(this));
  }
};

cursusWidget.formatSolde = function(soldeStr) {
  if (typeof soldeStr !== "string" || soldeStr.length === 0) {
    return soldeStr;
  } else if (soldeStr.length === 1) {
    return "0,0" + soldeStr;
  } else if (soldeStr.length === 2) {
    return "0," + soldeStr;
  } else {
    return (soldeStr.substring(0, soldeStr.length - 2)) + "," + (soldeStr.substring(soldeStr.length - 2));
  }
};


/* INIT */

(function() {
  var m;
  m = cursusWidget.model;
  return m.getCardNb(function() {
    return this.loadingWrapper(this.getSales());
  });
})();
