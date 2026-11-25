"""B2B mode of the stand-in factory (b2c_standin.FakeFactory(b2b=True)): the proposal and the load of the B2B scenario's three files (SAP business partners, Salesforce
accounts, website requests). The routes, the Data Services tables, the jobs, the review and the bulk accept are the ones of b2c_standin.py; only what the factory PROPOSES and
LOADS is B2B here. It is NOT a model of EBX: it answers what tools/e2e/b2b_scenario.py asks, in the shapes read from the live B2C runs.

What a good run proposes: three rule entities (SAP, SALESFORCE, WEB) superseded by one organisation entity (three members, fed by the three landing tables), and an address entity,
child of the organisation (feeds under the organisation's feeds, fed by SAP's address collection, Salesforce's billing fields and the web country), the identifiers (web SIREN
cleaned by NORMALISE_IDENTIFIER, SAP's tax collection, Salesforce's SIRET and VAT), COUNTRY_TO_ISO2 on the Salesforce and web country links, and four lines the bulk accept leaves for a
person (an attribute below the bar, one whose check proposes rejecting it, a script constraint, an external look-up). The load writes one organisation row per source row (360), the
SAP address collection exploded into address rows, one address per Salesforce account, the web country, every country as ISO2.

Defects (names given to FakeFactory): "orphan" (a rule entity left PROPOSED), "twomembers" (the organisation unifies two sources only), "sapnotfed" (SAP does not feed the organisation),
"noaddress" (no address entity), "addrnosf" / "addrnosap" (the address entity is not fed by Salesforce / SAP), "nocountrystep" (no step on the country links), "nosirenclean" (no
step on the web SIREN), "nosiren" (nothing reads the web SIREN column), "sirenspaces" (the web SIREN is loaded with its spaces), "badiso" (the Salesforce country is loaded as typed),
"wrongcountry" (Germany becomes AT), "sapnoaddr" (no address row from SAP), "sfaddrshort" (one Salesforce address row is missing), "reject" (a TransferReject row), "lowload" (SAP lands one
row: the OData envelope read as one document), "norows" (the organisation entity loads 359 rows).
"""
import json
import re
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2e_lib as L  # noqa: E402
import gen_b2b as G  # noqa: E402

COUNTRY_CODE = {}
for _iso, _name in G.COUNTRY_NAME.items():
    COUNTRY_CODE[_name.lower()] = _iso
for _iso, _texts in G.COUNTRY_FREE_TEXT.items():
    COUNTRY_CODE[_iso.lower()] = _iso
    for _t in _texts:
        COUNTRY_CODE[G.strip_accents(_t).lower()] = _iso


def to_iso2(text):
    return COUNTRY_CODE.get(G.strip_accents(text or "").strip().lower(), text)


def table_of(fk, code, app):
    for t in fk.table("LandingTable").values():
        if fk.source_of(t)["project"] == code and fk.source_of(t)["sourceSystem"] == app:
            return t
    raise KeyError(app)


def propose(fk, code):
    d = fk.defects
    tables = dict((app, table_of(fk, code, app)) for app in ("SAP", "SALESFORCE", "WEB"))
    columns = {}
    for app, t in tables.items():
        for c in fk.table("LandingColumn").values():
            if c["landingTable"] == t["id"]:
                columns[(app, c["sourceColumn"])] = c["id"]
    for i, app in enumerate(("SAP", "SALESFORCE", "WEB")):
        fk.add("StagingEntity", {"id": str(301 + i), "project": code, "name": app + "V1", "origin": "RULE", "confidence": 90, "rationale": "One entity per table.",
                                 "status": "PROPOSED" if ("orphan" in d and app == "WEB") else "SUPERSEDED", "memberEntities": []})
    members = ["301", "302", "303"][:2] if "twomembers" in d else ["301", "302", "303"]
    fk.add("StagingEntity", {"id": "311", "project": code, "name": "Organisation", "origin": "LLM", "confidence": 92, "status": "PROPOSED", "memberEntities": members,
                             "rationale": "Organisations."})
    org_apps = [a for a in ("SAP", "SALESFORCE", "WEB") if not (a == "SAP" and "sapnotfed" in d)]
    for app in org_apps:
        fk.add("TransferFeed", {"id": "fd" + app, "entity": "311", "landingTable": tables[app]["id"], "status": "PROPOSED"})
    address_apps = []
    if "noaddress" not in d:
        fk.add("StagingEntity", {"id": "312", "project": code, "name": "OrganisationAddress", "origin": "LLM", "confidence": 90, "status": "PROPOSED", "memberEntities": [],
                                 "rationale": "Postal addresses of the organisations."})
        address_apps = [a for a in ("SAP", "SALESFORCE", "WEB") if not (a == "SALESFORCE" and "addrnosf" in d) and not (a == "SAP" and "addrnosap" in d)]
        for app in address_apps:
            fk.add("TransferFeed", {"id": "fdAddr" + app, "entity": "312", "landingTable": tables[app]["id"], "status": "PROPOSED", "parentFeed": "fd" + app})
    ids = {}

    def attribute(entity, name, confidence=93, rationale=""):
        ids[(entity, name)] = fk.add("StagingAttribute", {"entity": entity, "name": name, "derivedFrom": "", "derivation": "", "origin": "LLM", "confidence": confidence,
                                                          "status": "PROPOSED", "rationale": rationale})["id"]
    for name in ("legalName", "siren", "siret", "vatNumber", "industry", "website", "phone", "contactEmail"):
        attribute("311", name)
    attribute("311", "contactMessage", 40)
    attribute("311", "fax", 20, "To check: no source column feeds this field, it would stay empty on every row (suggestion: reject it).")
    if "noaddress" not in d:
        for name in ("street", "postalCode", "city", "country"):
            attribute("312", name)

    def link(entity, attr, app, column, feed, steps=(), pipeline=""):
        mid = fk.add("AttributeMapping", {"attribute": ids[(entity, attr)], "landingColumn": columns[(app, column)], "feed": feed, "pipeline": pipeline, "rationale": "Mapped.",
                                          "confidence": 95, "status": "PROPOSED"})["id"]
        for kind, argument in steps:
            fk.add("MappingStep", {"mapping": mid, "kind": kind, "argument": argument, "status": "PROPOSED"})
    if "SAP" in org_apps:
        link("311", "legalName", "SAP", "OrganizationBPName1", "fdSAP")
        link("311", "siren", "SAP", "to_BusinessPartnerTax", "fdSAP")
        link("311", "industry", "SAP", "Industry", "fdSAP")
    link("311", "legalName", "SALESFORCE", "Name", "fdSALESFORCE", [("TRIM", "")])
    link("311", "siret", "SALESFORCE", "SIRET__c", "fdSALESFORCE")
    link("311", "vatNumber", "SALESFORCE", "VAT_Number__c", "fdSALESFORCE")
    link("311", "industry", "SALESFORCE", "Industry", "fdSALESFORCE")
    link("311", "website", "SALESFORCE", "Website", "fdSALESFORCE")
    link("311", "phone", "SALESFORCE", "Phone", "fdSALESFORCE", [("STRIP_CHARS", " .")])
    link("311", "legalName", "WEB", "companyName", "fdWEB", [("TRIM", "")])
    if "nosiren" not in d:
        link("311", "siren", "WEB", "siren", "fdWEB", [] if "nosirenclean" in d else [("NORMALISE_IDENTIFIER", "")])
    link("311", "contactEmail", "WEB", "contactEmail", "fdWEB")
    link("311", "contactMessage", "WEB", "message", "fdWEB")
    if "noaddress" not in d:
        country_step = [] if "nocountrystep" in d else [("COUNTRY_TO_ISO2", "")]
        if "SAP" in address_apps:
            link("312", "street", "SAP", "to_BusinessPartnerAddress", "fdAddrSAP")
            link("312", "country", "SAP", "to_BusinessPartnerAddress", "fdAddrSAP")
        if "SALESFORCE" in address_apps:
            link("312", "street", "SALESFORCE", "BillingStreet", "fdAddrSALESFORCE")
            link("312", "postalCode", "SALESFORCE", "BillingPostalCode", "fdAddrSALESFORCE")
            link("312", "city", "SALESFORCE", "BillingCity", "fdAddrSALESFORCE")
            link("312", "country", "SALESFORCE", "BillingCountry", "fdAddrSALESFORCE", country_step)
        if "WEB" in address_apps:
            link("312", "country", "WEB", "country", "fdAddrWEB", country_step)
    elif "nocountrystep" not in d:
        pass
    fk.add("CapabilitySuggestion", {"id": "326", "project": code, "entity": "311", "attribute": ids[("311", "phone")], "nature": "SCRIPT_DERIVATION", "status": "PROPOSED",
                                    "binding": '{"value":"phone","defaultCountry":{"toBeProvided":"which country"}}'})
    fk.add("CapabilitySuggestion", {"id": "327", "project": code, "entity": "311", "attribute": ids[("311", "siren")], "nature": "SCRIPT_CONSTRAINT", "status": "PROPOSED", "binding": "{}"})
    fk.add("CapabilitySuggestion", {"id": "328", "project": code, "entity": "311", "attribute": ids[("311", "legalName")], "nature": "EXTERNAL_SERVICE", "status": "PROPOSED", "binding": "{}"})
    fk.job(code, "STAGING_PROPOSAL", "Proposal run finished.")


def sap_siren(row):
    for t in row["to_BusinessPartnerTax"]["results"]:
        if t["BPTaxType"] == "FR1":
            return t["BPTaxNumber"]
    return ""


def sap_vat(row):
    for t in row["to_BusinessPartnerTax"]["results"]:
        if t["BPTaxType"] != "FR1":
            return t["BPTaxNumber"]
    return ""


def load(fk, code):
    d = fk.defects
    org, addr = [], []
    web_count = 0
    for tid, rows in fk.files.items():
        table = fk.table("LandingTable")[tid]
        if fk.source_of(table)["project"] != code:
            continue
        app = fk.source_of(table)["sourceSystem"]
        for r in rows:
            if app == "SAP":
                name = r["OrganizationBPName1"] + ((" " + r["OrganizationBPName2"]) if r["OrganizationBPName2"] else "")
                org.append({"sourceSystem": app, "sourceId": r["BusinessPartner"], "legalName": name, "siren": sap_siren(r), "siret": "", "vatNumber": sap_vat(r),
                            "industry": r["Industry"], "website": "", "phone": "", "contactEmail": ""})
                if "sapnoaddr" not in d:
                    for a in r["to_BusinessPartnerAddress"]["results"]:
                        addr.append({"sourceSystem": app, "sourceId": r["BusinessPartner"] + "|" + a["AddressID"], "street": (a["HouseNumber"] + " " + a["StreetName"]).strip(),
                                     "postalCode": a["PostalCode"], "city": a["CityName"], "country": a["Country"]})
            elif app == "SALESFORCE":
                org.append({"sourceSystem": app, "sourceId": r["Id"], "legalName": r["Name"].strip(), "siren": "", "siret": r["SIRET__c"], "vatNumber": r["VAT_Number__c"],
                            "industry": r["Industry"], "website": r["Website"], "phone": re.sub(r"[ .]", "", r["Phone"]), "contactEmail": ""})
                country = r["BillingCountry"] if "badiso" in d else to_iso2(r["BillingCountry"])
                addr.append({"sourceSystem": app, "sourceId": r["Id"], "street": r["BillingStreet"], "postalCode": r["BillingPostalCode"], "city": r["BillingCity"], "country": country})
            else:
                siren = r["siren"] if "sirenspaces" in d else re.sub(r"\D", "", r["siren"])
                org.append({"sourceSystem": app, "sourceId": r["submissionId"], "legalName": r["companyName"].strip(), "siren": siren, "siret": "", "vatNumber": "", "industry": "",
                            "website": "", "phone": "", "contactEmail": r["contactEmail"]})
                country = to_iso2(r["country"])
                if "wrongcountry" in d and country == "DE":
                    country = "AT"
                addr.append({"sourceSystem": app, "sourceId": r["submissionId"], "street": "", "postalCode": "", "city": "", "country": country})
    if "norows" in d:
        org = org[:-1]
    if "sfaddrshort" in d:
        for i, a in enumerate(addr):
            if a["sourceSystem"] == "SALESFORCE":
                del addr[i]
                break
    tables = {"Organisation": org}
    if "noaddress" not in d:
        tables["OrganisationAddress"] = addr
    fk.staging[code] = tables
    for e in fk.table("StagingEntity").values():
        if e["project"] == code and e["status"] in ("ACCEPTED", "APPLIED"):
            n = len(tables.get(e["name"], []))
            e.update({"status": "APPLIED", "lastLoadWritten": n, "lastLoadRead": n, "lastLoadRejected": 0, "lastLoadUnchanged": 0, "lastLoadMessage": "%d written" % n})
    fk.apply_accepted_constraints(code)
    if "reject" in d:
        fk.add("TransferReject", {"project": code, "entity": "311", "sourceId": "x", "reasonCode": "convert.date|created|CreationDate", "category": "EBX_REFUSAL"})
    fk.step_row(code, "STAGING_LOAD")
    fk.job(code, "STAGING_LOAD_RUN", "Staging build finished.")
