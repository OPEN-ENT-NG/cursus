# À propos du widget Cursus

* licence : [AGPL v3](http://www.gnu.org/licenses/agpl.txt)
* financeur : Région Picardie
* description : La carte Picardie Cursus est une carte de paiement multi-services qui embarque les subventions de la région Picardie. Elle peut être utilisée comme moyen de paiement chez les partenaires de la région Picardie.Le widget Picardie Cursus est créé dans l’ENT LEO pour permettre aux élèves d’accéder facilement et rapidement aux informations de leur carte Picardie Cursus. Le service technique de paiement et de subvention est fourni par [Moneo / Applicam](http://www.moneo.com/moneo-applicam/solutions/gestion-des-aides-et-des-subventions)

## Détails techniques

#### Ajouts dans le springboard

**build.gradle**

`deployment "org.entcore:cursus:$cursusVersion:deployment"`

**gradle.properties**

`cursusVersion=[VERSION]`

**test/conf.properties**

```groovy
widgets=[autres widgets...], {"name": "cursus", "path": "/cursus/public/template/cursus-widget.html", "js": "/cursus/public/js/cursus-widget.js", "i18n": "/cursus/i18n"}
cursusEndpoint=[ENDPOINT]
authConf=[CONF]
```

#### Champs spécifiques pour le déploiement

```json
"webserviceEndpoint": "[Adresse de la racine du WebService]",
"authConf": {
	"numSite": "[Numéro de partenaire]",
	"authentification": {
		"typeIndividu": "[Type]",
		"idIndividu": "[Id]",
		"pwd": "[Mot de passe]"
	}
}
```

*Mapping dans conf/test.properties du springboard :*

`"webserviceEndpoint": ${cursusEndpoint}`
`"authConf": ${cursusAuthConf}`
