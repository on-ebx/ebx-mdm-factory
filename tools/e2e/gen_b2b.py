#!/usr/bin/env python3
"""Three fictional B2B account files for an MDM Factory end-to-end test, with realistic overlaps (docs/E2E-B2B.md). Python 3 standard library only, seeded and
deterministic: the same files, byte for byte, on every run.

    tools/e2e/gen_b2b.py OUT_DIR [--sap-envelope odata|results|array] [--web-envelope submissions|array]

Every company is INVENTED (a brand made of syllables + a sector word + a legal form): any resemblance to a real company is a coincidence. SIRENs are random,
Luhn-valid numbers, not registered ones. The people of the web form are invented first names and surnames.

THE THREE SOURCES (the application code of each, then the file)
  SAP        SAP_BusinessPartner.json  SAP S/4HANA Business Partner, OData v2 shape {"d": {"results": [...]}}: 150 organisations, `BusinessPartner` (10 digits, leading
                                       zeros), category "2", the legal name cut at 40 characters (Name1, the rest in Name2), nested `to_BusinessPartnerAddress` (1-2
                                       addresses) and `to_BusinessPartnerTax` (BPTaxType FR1 = SIREN, FR0 = French VAT, DE0 / ES0 / IT0 / BE0 = the VAT of the other
                                       countries)
  SALESFORCE SF_Accounts.csv           Salesforce Account export, `,` separator, every field quoted: 120 rows, 18-character Id, Name, the billing address with the
                                       street number inside the street, the country as a NAME ("France"), mixed phone formats, a Salesforce picklist Industry,
                                       SIRET__c (sometimes empty), VAT_Number__c, and SAP_BP__c = the SAP number for 30 % of the rows (the cross-reference an
                                       organisation keeps by hand)
  WEB        WEB_AccountRequests.json  website B2B form submissions, {"submissions": [...]}: 90 rows, the company name typed freely, the SIREN typed by the user (with
                                       spaces, sometimes a wrong checksum, sometimes empty), the country as free text ("FR", "france", "Deutschland"), a contact
                                       person, a corporate e-mail address for most

THE OVERLAPS (the ground truth is written next to the files, truth_b2b.json, and returned by generate())
  190 companies in all:  48 in the three sources, 40 in SAP + Salesforce, 14 in SAP + web, 12 in Salesforce + web (114 companies = 60 % in at least two sources, 48 = 25 %
  in all three), 48 only in SAP, 15 only in Salesforce, 13 only on the website (prospects). Salesforce holds 5 pure duplicates (two accounts for the same company), the
  website 3 (the same company submitting twice): 150 + 120 + 90 = 360 rows for 190 companies. The same company is written differently in each source: legal form kept /
  dropped / "S.A.S.", upper case, accents dropped, abbreviations (Sté, Ets, Cie), addresses with "bd" for "boulevard" and sometimes no house number, the SIREN present in
  SAP (tax FR1), in the SIRET of Salesforce (the SIREN is its first nine digits) and in the web form (typed). Salesforce is linked to SAP by SAP_BP__c for 36 of its rows.
"""
import csv
import datetime
import io
import json
import os
import random
import re
import sys
import unicodedata
import uuid

SEED = 20261022

SAP_FILE = "SAP_BusinessPartner.json"
SF_FILE = "SF_Accounts.csv"
WEB_FILE = "WEB_AccountRequests.json"
TRUTH_FILE = "truth_b2b.json"
NAMES = {"SAP": SAP_FILE, "SALESFORCE": SF_FILE, "WEB": WEB_FILE}

SF_COLUMNS = ["Id", "Name", "BillingStreet", "BillingPostalCode", "BillingCity", "BillingCountry", "Phone", "Website", "Industry", "NumberOfEmployees",
              "AnnualRevenue", "SIRET__c", "VAT_Number__c", "SAP_BP__c"]

# (name, sources, how many companies)
GROUPS = [("ALL3", ("SAP", "SALESFORCE", "WEB"), 48), ("SAP+SF", ("SAP", "SALESFORCE"), 40), ("SAP+WEB", ("SAP", "WEB"), 14), ("SF+WEB", ("SALESFORCE", "WEB"), 12),
          ("SAP", ("SAP",), 48), ("SF", ("SALESFORCE",), 15), ("WEB", ("WEB",), 13)]
SF_DUPLICATES = 5
WEB_DUPLICATES = 3
SF_SAP_LINKS = 36            # 30 % of the 120 Salesforce rows

COUNTRY_WEIGHTS = [("FR", 85), ("DE", 5), ("ES", 4), ("IT", 3), ("BE", 3)]
COUNTRY_NAME = {"FR": "France", "DE": "Germany", "ES": "Spain", "IT": "Italy", "BE": "Belgium"}
COUNTRY_FREE_TEXT = {"FR": ["FR", "france", "France", "FRANCE", "Fr", "France"], "DE": ["Deutschland", "germany", "DE", "Allemagne"],
                     "ES": ["Espagne", "spain", "ES", "España"], "IT": ["Italie", "Italy", "IT", "Italia"], "BE": ["Belgique", "BE", "belgium", "Belgien"]}

SYLLABLES = ["val", "mor", "qui", "bra", "vel", "or", "lan", "tis", "sen", "dar", "pel", "nov", "cor", "ber", "lum", "fal", "gar", "ren", "tav", "zin", "mar", "sol", "ket",
             "ari", "dov", "eli", "fen", "hav", "jor", "kal", "oxe", "pri", "ust", "wen", "yar", "zel", "bol", "cim", "dru", "fio"]
# a sector: (fr, de, es, it, en, SAP industry key, Salesforce Industry picklist value)
SECTORS = [("Logistique", "Logistik", "Logística", "Logistica", "Logistics", "TRAN", "Transportation"),
           ("Industries", "Industrie", "Industrias", "Industrie", "Industries", "MACH", "Machinery"),
           ("Conseil", "Beratung", "Consultoría", "Consulenza", "Consulting", "SERV", "Consulting"),
           ("Mécanique", "Maschinenbau", "Mecánica", "Meccanica", "Mechanics", "MACH", "Machinery"),
           ("Énergie", "Energie", "Energía", "Energia", "Energy", "ENER", "Energy"),
           ("Chimie", "Chemie", "Química", "Chimica", "Chemicals", "CHEM", "Chemicals"),
           ("Électronique", "Elektronik", "Electrónica", "Elettronica", "Electronics", "ELEC", "Electronics"),
           ("Bâtiment", "Bau", "Construcción", "Costruzioni", "Construction", "CONS", "Construction"),
           ("Médical", "Medizintechnik", "Médica", "Medicale", "Medical", "HLTH", "Healthcare"),
           ("Agroalimentaire", "Lebensmittel", "Alimentación", "Alimentare", "Foods", "FOOD", "Food & Beverage"),
           ("Distribution", "Handel", "Distribución", "Distribuzione", "Retail", "RETL", "Retail"),
           ("Emballages", "Verpackung", "Envases", "Imballaggi", "Packaging", "CHEM", "Chemicals")]
LEGAL_FORMS = {"FR": ["SAS", "SARL", "SA", "SASU", "EURL", "SNC"], "DE": ["GmbH", "AG"], "ES": ["S.L.", "S.A."], "IT": ["S.r.l.", "S.p.A."], "BE": ["SRL", "NV"]}
LONG_PREFIX = {"FR": "Compagnie Générale de", "DE": "Allgemeine Gesellschaft für", "ES": "Compañía General de", "IT": "Compagnia Generale di", "BE": "Compagnie Générale de"}
CITIES = {"FR": [("Paris", "75", "75"), ("Lyon", "69", "69"), ("Marseille", "13", "13"), ("Toulouse", "31", "31"), ("Nantes", "44", "44"), ("Bordeaux", "33", "33"),
                 ("Lille", "59", "59"), ("Strasbourg", "67", "67"), ("Rennes", "35", "35"), ("Grenoble", "38", "38"), ("Nancy", "54", "54"), ("Dijon", "21", "21"),
                 ("Rouen", "76", "76"), ("Tours", "37", "37"), ("Montpellier", "34", "34")],
          "DE": [("Berlin", "10115", "BE"), ("München", "80331", "BY"), ("Hamburg", "20095", "HH"), ("Köln", "50667", "NW"), ("Frankfurt am Main", "60311", "HE"),
                 ("Stuttgart", "70173", "BW")],
          "ES": [("Madrid", "28001", "M"), ("Barcelona", "08001", "B"), ("Valencia", "46001", "V"), ("Sevilla", "41001", "SE")],
          "IT": [("Milano", "20121", "MI"), ("Roma", "00184", "RM"), ("Torino", "10121", "TO")],
          "BE": [("Bruxelles", "1000", "BRU"), ("Anvers", "2000", "VAN"), ("Gand", "9000", "VOV")]}
STREETS = {"FR": ["rue de la République", "avenue Jean Jaurès", "boulevard Voltaire", "rue Victor Hugo", "avenue de la Gare", "rue du Commerce", "boulevard Haussmann",
                  "rue des Lilas", "quai Aristide Briand", "route de Lyon", "avenue du Général Leclerc", "boulevard de la Liberté", "place du Marché", "rue Pasteur"],
           "DE": ["Hauptstraße", "Bahnhofstraße", "Industriestraße", "Gartenweg", "Marktplatz"], "ES": ["Calle Mayor", "Avenida de la Constitución", "Calle del Comercio"],
           "IT": ["Via Roma", "Corso Italia", "Via dell'Industria"], "BE": ["Rue Royale", "Avenue Louise", "Rue du Commerce"]}
ABBREVIATIONS = [("boulevard", "bd"), ("avenue", "av."), ("rue", "r."), ("place", "pl."), ("quai", "q."), ("route", "rte")]
WORD_ABBREVIATIONS = [("Société", "Sté"), ("Compagnie", "Cie"), ("Établissements", "Ets"), ("Industries", "Ind."), ("Générale", "Gale"), ("Générale de", "Gle de")]
FIRST_NAMES = ["Camille", "Louise", "Léa", "Manon", "Chloé", "Inès", "Jade", "Emma", "Sarah", "Julie", "Lucas", "Hugo", "Louis", "Gabriel", "Arthur", "Jules", "Nathan",
               "Thomas", "Théo", "Paul", "Hélène", "Céline", "Nicolas", "Antoine", "Mathilde", "Élodie", "Bastien", "Claire", "Markus", "Elena", "Marco", "Sofia", "Jan"]
LAST_NAMES = ["Martin", "Bernard", "Dubois", "Thomas", "Robert", "Richard", "Petit", "Durand", "Leroy", "Moreau", "Simon", "Laurent", "Lefèvre", "Michel", "Garcia", "David",
              "Bertrand", "Roux", "Vincent", "Fournier", "Morel", "Girard", "André", "Mercier", "Dupont", "Lambert", "Bonnet", "Weber", "Rossi", "Peeters"]
JOB_TITLES = ["Directeur achats", "Responsable logistique", "Gérant", "DAF", "Acheteur", "Responsable qualité", "Directeur général", "Chargée de projets", "Head of procurement"]
MESSAGES = ["Nous souhaitons ouvrir un compte client.", "Demande de tarifs pour un premier volume.", "Merci de nous recontacter pour une démonstration.",
            "Ouverture de compte pour notre nouvelle filiale.", "Besoin d'un devis sous quinze jours.", "", "Nous avons rencontré vos équipes au salon.", ""]
UTM = ["google", "linkedin", "newsletter", "salon", "partner", "direct", ""]
MAIL_PUBLIC = ["gmail.com", "outlook.com", "hotmail.fr", "yahoo.fr"]

# the brands of well-known companies this generator must never produce (the self-test checks the files against it)
DENYLIST = ["total", "renault", "airbus", "michelin", "danone", "carrefour", "orange", "sanofi", "thales", "siemens", "bosch", "basf", "iberdrola", "telefonica", "fiat",
            "pirelli", "solvay", "peugeot", "valeo", "saint-gobain", "schneider", "vinci", "bouygues"]


# --------------------------------------------------------------------------------------------------------------------- identifiers

def luhn_ok(digits):
    """True when `digits` (a string of digits) passes the Luhn check (SIREN, SIRET, Italian VAT...)."""
    if not digits or not digits.isdigit():
        return False
    total = 0
    for i, ch in enumerate(reversed(digits)):
        d = int(ch)
        if i % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return total % 10 == 0


def luhn_check_digit(body):
    for d in range(10):
        if luhn_ok(body + str(d)):
            return str(d)
    raise ValueError(body)


def siren_valid(value):
    return bool(value) and len(value) == 9 and luhn_ok(value)


def siret_valid(value):
    return bool(value) and len(value) == 14 and luhn_ok(value)


def fr_vat_key(siren):
    """The two-digit key of a French VAT number: (12 + 3 * (SIREN mod 97)) mod 97."""
    return "%02d" % ((12 + 3 * (int(siren) % 97)) % 97)


def fr_vat(siren):
    return "FR" + fr_vat_key(siren) + siren


def fr_vat_valid(value):
    compact = re.sub(r"[\s.\-]", "", (value or "").upper())
    return bool(re.match(r"^FR\d{11}$", compact)) and compact[2:4] == fr_vat_key(compact[4:])


def make_siren(rng, used):
    while True:
        body = str(rng.randint(1, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(7))
        siren = body + luhn_check_digit(body)
        if siren not in used:
            used.add(siren)
            return siren


def make_siret(rng, siren):
    body = siren + "".join(str(rng.randint(0, 9)) for _ in range(4))
    return body + luhn_check_digit(body)


def make_foreign_vat(rng, country):
    if country == "DE":
        return "DE" + str(rng.randint(1, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(8))
    if country == "ES":
        return "ES" + rng.choice("ABCDEFGH") + "".join(str(rng.randint(0, 9)) for _ in range(7)) + rng.choice("0123456789ABCDEFGHIJ")
    if country == "IT":
        body = "".join(str(rng.randint(0, 9)) for _ in range(10))
        return "IT" + body + luhn_check_digit(body)
    body = "0" + "".join(str(rng.randint(0, 9)) for _ in range(6)) + str(rng.randint(1, 9))      # BE: 8 digits then 97 - (they mod 97)
    return "BE" + body + "%02d" % (97 - int(body) % 97)


def sf_id(rng):
    """An 18-character Salesforce Account Id: '001' + 12 base-62 characters + the 3-character case checksum."""
    alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    base = "001" + "".join(rng.choice(alphabet) for _ in range(12))
    suffix = ""
    for chunk in (base[0:5], base[5:10], base[10:15]):
        bits = sum((1 << i) for i, ch in enumerate(chunk) if ch.isupper())
        suffix += "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"[bits]
    return base + suffix


# --------------------------------------------------------------------------------------------------------------------- text helpers

def strip_accents(text):
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", strip_accents(text).lower()).strip("-")


def phone_fr(rng):
    return "0" + str(rng.randint(1, 5)) + "".join(str(rng.randint(0, 9)) for _ in range(8))


def mobile_fr(rng):
    return "0" + str(rng.choice([6, 7])) + "".join(str(rng.randint(0, 9)) for _ in range(8))


def fmt_fr_phone(p, style):
    if style == 0:
        return p
    if style == 1:
        return " ".join(p[i:i + 2] for i in range(0, 10, 2))
    if style == 2:
        return "+33 " + p[1] + " " + " ".join(p[i:i + 2] for i in range(2, 10, 2))
    if style == 3:
        return ".".join(p[i:i + 2] for i in range(0, 10, 2))
    return "(0)" + p[1] + " " + " ".join(p[i:i + 2] for i in range(2, 10, 2))


INTL_PREFIX = {"DE": "+49", "ES": "+34", "IT": "+39", "BE": "+32"}


def company_phone(rng, c, style):
    if c["country"] == "FR":
        return fmt_fr_phone(c["phone"], style)
    return "%s %s %s" % (INTL_PREFIX[c["country"]], c["phone"][:2], c["phone"][2:]) if style % 2 == 0 else INTL_PREFIX[c["country"]] + c["phone"]


def street_variant(rng, number, street, drop_number_p=0.1, abbreviate_p=0.4):
    text = street
    if rng.random() < abbreviate_p:
        for long, short in ABBREVIATIONS:
            if text.lower().startswith(long + " "):
                text = short + text[len(long):]
                break
    if rng.random() < drop_number_p:
        return text
    return "%s %s" % (number, text)


# --------------------------------------------------------------------------------------------------------------------- the companies

def pick_country(rng):
    total = sum(w for _c, w in COUNTRY_WEIGHTS)
    x = rng.randint(1, total)
    for country, weight in COUNTRY_WEIGHTS:
        x -= weight
        if x <= 0:
            return country
    return "FR"


def make_address(rng, country, city=None):
    c = city or rng.choice(CITIES[country])
    name, postal, region = c
    if country == "FR":
        postal = ("750%02d" % rng.randint(1, 20)) if name == "Paris" else postal + "%03d" % rng.randint(0, 999)
    return {"number": str(rng.randint(1, 180)), "street": rng.choice(STREETS[country]), "postal": postal, "city": name, "region": region, "country": country, "_city": c}


def make_company(rng, index, used_brands, used_sirens, sources):
    country = pick_country(rng)
    while True:
        brand = "".join(rng.choice(SYLLABLES) for _ in range(rng.choice([2, 2, 3]))).capitalize()
        if brand.lower() not in used_brands and not any(d in brand.lower() for d in DENYLIST):
            used_brands.add(brand.lower())
            break
    lang = {"FR": 0, "BE": 0, "DE": 1, "ES": 2, "IT": 3}[country]
    sector_index = rng.randrange(len(SECTORS))
    sector = SECTORS[sector_index]
    form = rng.choice(LEGAL_FORMS[country])
    if rng.random() < 0.12:       # a long legal name: SAP cuts it at 40 characters
        legal = "%s %s %s Europe %s" % (LONG_PREFIX[country], sector[lang], brand, form)
    elif country == "FR" and rng.random() < 0.08:
        legal = "Établissements %s %s" % (brand, form)
    else:
        legal = "%s %s %s" % (brand, sector[lang], form)
    domain = "%s-%s.%s" % (slug(brand), slug(sector[lang]), "com" if rng.random() < 0.2 else country.lower())
    addresses = [make_address(rng, country)]
    if rng.random() < 0.35:
        second = make_address(rng, country, addresses[0]["_city"] if rng.random() < 0.5 else None)
        addresses.append(second)
    employees = rng.choice([8, 15, 24, 42, 60, 110, 180, 320, 560, 1200])
    c = {"index": index, "brand": brand, "sector": sector_index, "lang": lang, "country": country, "form": form, "legal": legal, "domain": domain, "sources": sources,
         "addresses": addresses, "employees": employees, "revenue": employees * rng.randint(60, 250) * 1000, "phone": phone_fr(rng) if country == "FR" else
         "".join(str(rng.randint(0, 9)) for _ in range(9)), "created": datetime.date(rng.randint(2015, 2025), rng.randint(1, 12), rng.randint(1, 28)),
         "siren": "", "siret": "", "vat": ""}
    if country == "FR":
        c["siren"] = make_siren(rng, used_sirens)
        c["siret"] = make_siret(rng, c["siren"])
        c["vat"] = fr_vat(c["siren"])
    else:
        c["vat"] = make_foreign_vat(rng, country)
    return c


def name_variant(rng, c, style):
    """The company's name as one source writes it. style: exact | no_form | upper | no_accent | abbrev | sas_dots | lower | typo."""
    name = c["legal"]
    form = c["form"]
    without_form = name[:-len(form)].rstrip() if name.endswith(form) else name
    if style == "exact":
        return name
    if style == "no_form":
        return without_form
    if style == "upper":
        return name.upper()
    if style == "no_accent":
        return strip_accents(name)
    if style == "abbrev":
        out = name
        for long, short in WORD_ABBREVIATIONS:
            if long in out:
                out = out.replace(long, short, 1)
                break
        return out.replace(" SAS", " S.A.S.") if c["form"] == "SAS" and out == name else out
    if style == "sas_dots":
        return without_form + " " + ".".join(form.replace(".", "")) + "."
    if style == "lower":
        return strip_accents(without_form).lower()
    if style == "typo":
        letters = list(without_form)
        i = rng.randrange(1, max(2, len(letters) - 1))
        letters[i], letters[i - 1] = letters[i - 1], letters[i]
        return "".join(letters)
    return name


def pick_style(rng, source):
    table = {"SALESFORCE": [("exact", 40), ("no_form", 20), ("upper", 10), ("no_accent", 10), ("abbrev", 20)],
             "WEB": [("no_form", 25), ("lower", 20), ("sas_dots", 15), ("no_accent", 20), ("exact", 10), ("typo", 10)]}[source]
    total = sum(w for _s, w in table)
    x = rng.randint(1, total)
    for style, weight in table:
        x -= weight
        if x <= 0:
            return style
    return "exact"


# --------------------------------------------------------------------------------------------------------------------- the three files

def odata_date(day):
    ms = int(datetime.datetime(day.year, day.month, day.day, tzinfo=datetime.timezone.utc).timestamp() * 1000)
    return "/Date(%d)/" % ms


def build(seed=SEED):
    """(sap_rows, sf_rows, web_rows, truth): the rows as dicts (the SAP rows nested), and the ground truth."""
    rng = random.Random(seed)
    used_brands, used_sirens = set(), set()
    companies = []
    for group, sources, count in GROUPS:
        for _ in range(count):
            c = make_company(rng, len(companies), used_brands, used_sirens, sources)
            c["group"] = group
            companies.append(c)
    # who gets two Salesforce accounts, who submits the form twice (drawn from the companies that are in that source)
    in_sf = [c for c in companies if "SALESFORCE" in c["sources"]]
    in_web = [c for c in companies if "WEB" in c["sources"]]
    sf_dups = set(c["index"] for c in rng.sample(in_sf, SF_DUPLICATES))
    web_dups = set(c["index"] for c in rng.sample(in_web, WEB_DUPLICATES))

    rows_of = dict((c["index"], {"SAP": [], "SALESFORCE": [], "WEB": []}) for c in companies)

    # ---- SAP
    bp_numbers = rng.sample(range(1000000, 1999999), 150)
    sap_rows = []
    bp_of = {}
    for c, number in zip([c for c in companies if "SAP" in c["sources"]], bp_numbers):
        bp = "%010d" % number
        bp_of[c["index"]] = bp
        name1, name2 = c["legal"], ""
        if len(name1) > 40:
            name1 = c["legal"][:40].rstrip()
            name2 = c["legal"][len(name1):].strip()
        addresses = []
        for n, a in enumerate(c["addresses"]):
            addresses.append({"AddressID": str(n + 1), "StreetName": a["street"], "HouseNumber": a["number"], "PostalCode": a["postal"], "CityName": a["city"],
                              "Country": a["country"], "Region": a["region"]})
        taxes = []
        if c["country"] == "FR":
            taxes.append({"BusinessPartner": bp, "BPTaxType": "FR1", "BPTaxNumber": c["siren"]})
            if rng.random() < 0.8:
                taxes.append({"BusinessPartner": bp, "BPTaxType": "FR0", "BPTaxNumber": c["vat"]})
        else:
            taxes.append({"BusinessPartner": bp, "BPTaxType": c["country"] + "0", "BPTaxNumber": c["vat"]})
        sap_rows.append({"BusinessPartner": bp, "BusinessPartnerCategory": "2", "BusinessPartnerGrouping": rng.choice(["BP01", "BP02", "Z001"]),
                         "OrganizationBPName1": name1, "OrganizationBPName2": name2, "SearchTerm1": strip_accents(c["brand"]).upper()[:20],
                         "Industry": SECTORS[c["sector"]][5], "LegalForm": c["form"], "CreationDate": odata_date(c["created"]),
                         "to_BusinessPartnerAddress": {"results": addresses}, "to_BusinessPartnerTax": {"results": taxes}})
        rows_of[c["index"]]["SAP"].append(bp)
    sap_rows.sort(key=lambda r: r["BusinessPartner"])

    # ---- Salesforce
    sf_rows = []
    sf_company = []                 # the company of each Salesforce row, in order
    for c in in_sf:
        for copy in range(2 if c["index"] in sf_dups else 1):
            style = pick_style(rng, "SALESFORCE")
            address = c["addresses"][-1] if (len(c["addresses"]) > 1 and rng.random() < 0.15) else c["addresses"][0]
            country_name = COUNTRY_NAME[c["country"]]
            if c["country"] == "FR" and rng.random() < 0.1:
                country_name = rng.choice(["FRANCE", "france"])
            site = c["domain"]
            website = rng.choice(["https://www." + site, "www." + site, site, "http://" + site])
            row = {"Id": sf_id(rng), "Name": name_variant(rng, c, style), "BillingStreet": street_variant(rng, address["number"], address["street"], 0.1, 0.4),
                   "BillingPostalCode": address["postal"], "BillingCity": address["city"] if rng.random() > 0.15 else strip_accents(address["city"]).upper(),
                   "BillingCountry": country_name, "Phone": company_phone(rng, c, rng.randrange(5)), "Website": website,
                   "Industry": SECTORS[c["sector"]][6], "NumberOfEmployees": str(c["employees"]),
                   "AnnualRevenue": str(int(c["revenue"] * (1 if copy == 0 else rng.choice([0.97, 1.03])))),
                   "SIRET__c": c["siret"] if (c["country"] == "FR" and rng.random() > 0.25) else "",
                   "VAT_Number__c": "", "SAP_BP__c": ""}
            if rng.random() > 0.25:
                vat = c["vat"]
                row["VAT_Number__c"] = vat if rng.random() > 0.2 else (vat[:2] + " " + " ".join(vat[i:i + 3] for i in range(2, len(vat), 3)))
            sf_rows.append(row)
            sf_company.append((c, copy))
    # the SAP number the organisation keeps by hand: 36 rows of companies that are in SAP (never on the duplicate)
    candidates = [i for i, (c, copy) in enumerate(sf_company) if "SAP" in c["sources"] and copy == 0]
    for i in rng.sample(candidates, SF_SAP_LINKS):
        sf_rows[i]["SAP_BP__c"] = bp_of[sf_company[i][0]["index"]]
    sf_company_order = list(range(len(sf_rows)))
    rng.shuffle(sf_company_order)
    sf_rows = [sf_rows[i] for i in sf_company_order]
    sf_company = [sf_company[i] for i in sf_company_order]
    for row, (c, _copy) in zip(sf_rows, sf_company):
        rows_of[c["index"]]["SALESFORCE"].append(row["Id"])

    # ---- website form
    web_rows = []
    web_valid_sirens = 0
    siren_kinds = {"clean": 0, "spaced": 0, "empty": 0, "wrongChecksum": 0}
    web_company = []
    for c in in_web:
        for copy in range(2 if c["index"] in web_dups else 1):
            style = pick_style(rng, "WEB")
            kind = "empty"
            siren = ""
            if c["country"] == "FR":
                x = rng.random()
                if x < 0.30:
                    kind, siren = "clean", c["siren"]
                elif x < 0.70:
                    kind, siren = "spaced", " ".join([c["siren"][:3], c["siren"][3:6], c["siren"][6:]])
                elif x < 0.85:
                    kind, siren = "empty", ""
                else:
                    kind = "wrongChecksum"
                    siren = c["siren"][:8] + str((int(c["siren"][8]) + rng.randint(1, 9)) % 10)
            siren_kinds[kind] += 1
            if kind in ("clean", "spaced"):
                web_valid_sirens += 1
            first, last = rng.choice(FIRST_NAMES), rng.choice(LAST_NAMES)
            mail_domain = c["domain"] if rng.random() < 0.8 else rng.choice(MAIL_PUBLIC)
            month, day = rng.randint(1, 10), rng.randint(1, 28)
            offset = "+02:00" if 4 <= month <= 9 else "+01:00"
            phone = mobile_fr(rng) if c["country"] == "FR" else None
            contact_phone = ""
            if phone:
                contact_phone = rng.choice(["+33 " + phone[1] + " " + " ".join(phone[i:i + 2] for i in range(2, 10, 2)), " ".join(phone[i:i + 2] for i in range(0, 10, 2)),
                                            "+33" + phone[1:], phone])
            else:
                contact_phone = INTL_PREFIX[c["country"]] + " " + "".join(str(rng.randint(0, 9)) for _ in range(9))
            web_rows.append({"submissionId": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                             "submittedAt": "2026-%02d-%02dT%02d:%02d:%02d%s" % (month, day, rng.randint(7, 19), rng.randint(0, 59), rng.randint(0, 59), offset),
                             "companyName": name_variant(rng, c, style), "siren": siren, "country": rng.choice(COUNTRY_FREE_TEXT[c["country"]]),
                             "contactFirstName": first, "contactLastName": last,
                             "contactEmail": "%s.%s@%s" % (slug(first), slug(last), mail_domain), "contactPhone": contact_phone,
                             "jobTitle": rng.choice(JOB_TITLES), "message": rng.choice(MESSAGES), "consentMarketing": rng.random() < 0.55, "utmSource": rng.choice(UTM)})
            web_company.append(c)
    order = sorted(range(len(web_rows)), key=lambda i: web_rows[i]["submittedAt"])
    web_rows = [web_rows[i] for i in order]
    web_company = [web_company[i] for i in order]
    for row, c in zip(web_rows, web_company):
        rows_of[c["index"]]["WEB"].append(row["submissionId"])

    truth = truth_of(companies, rows_of, sf_rows, sap_rows, web_rows, siren_kinds, web_valid_sirens, sf_dups, web_dups)
    return sap_rows, sf_rows, web_rows, truth


def truth_of(companies, rows_of, sf_rows, sap_rows, web_rows, siren_kinds, web_valid_sirens, sf_dups, web_dups):
    people = []
    for c in companies:
        people.append({"company": c["index"], "name": c["legal"], "group": c["group"], "country": c["country"], "siren": c["siren"], "siret": c["siret"], "vat": c["vat"], "domain": c["domain"],
                       "sources": dict((s, rows_of[c["index"]][s]) for s in ("SAP", "SALESFORCE", "WEB"))})
    n = len(companies)
    multi = sum(1 for c in companies if len(c["sources"]) >= 2)
    all3 = sum(1 for c in companies if len(c["sources"]) == 3)
    country_of_key = {}
    for p in people:
        for source in ("SALESFORCE", "WEB"):
            for key in p["sources"][source]:
                country_of_key[key] = p["country"]
    summary = {"seed": SEED, "companies": n, "rows": {"SAP": len(sap_rows), "SALESFORCE": len(sf_rows), "WEB": len(web_rows)},
               "groups": dict((g, k) for g, _s, k in GROUPS), "inAtLeastTwoSources": multi, "inAtLeastTwoSourcesPercent": round(100.0 * multi / n, 1),
               "inAllThreeSources": all3, "inAllThreePercent": round(100.0 * all3 / n, 1), "onlyOnTheWebsite": sum(1 for c in companies if c["sources"] == ("WEB",)),
               "salesforceDuplicateCompanies": len(sf_dups), "webDuplicateCompanies": len(web_dups),
               "salesforceRowsLinkedToSap": sum(1 for r in sf_rows if r["SAP_BP__c"]), "salesforceRowsWithSiret": sum(1 for r in sf_rows if r["SIRET__c"]),
               "salesforceRowsWithVat": sum(1 for r in sf_rows if r["VAT_Number__c"]), "webSirens": dict(siren_kinds, usableAfterCleaning=web_valid_sirens),
               "sapAddresses": sum(len(r["to_BusinessPartnerAddress"]["results"]) for r in sap_rows),
               "sapLongNames": sum(1 for r in sap_rows if r["OrganizationBPName2"]),
               "expectedIso2": {"WEB": dict((k, v) for k, v in country_of_key.items() if k in set(r["submissionId"] for r in web_rows)),
                                "SALESFORCE": dict((k, v) for k, v in country_of_key.items() if k in set(r["Id"] for r in sf_rows))}}
    return {"summary": summary, "companies": people}


def sap_document(rows, envelope="odata"):
    if envelope == "array":
        return rows
    if envelope == "results":
        return {"results": rows}
    return {"d": {"results": rows}}


def web_document(rows, envelope="submissions"):
    return rows if envelope == "array" else {"submissions": rows}


def write(out_dir, sap_envelope="odata", web_envelope="submissions", seed=SEED):
    """Writes the three files and truth_b2b.json into `out_dir`; returns ({app: path}, truth)."""
    os.makedirs(out_dir, exist_ok=True)
    sap_rows, sf_rows, web_rows, truth = build(seed)
    paths = dict((app, os.path.join(out_dir, name)) for app, name in NAMES.items())
    with open(paths["SAP"], "w", encoding="utf-8") as fh:
        json.dump(sap_document(sap_rows, sap_envelope), fh, ensure_ascii=False, indent=1)
    with open(paths["SALESFORCE"], "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, quoting=csv.QUOTE_ALL, lineterminator="\n")
        w.writerow(SF_COLUMNS)
        for r in sf_rows:
            w.writerow([r[c] for c in SF_COLUMNS])
    with open(paths["WEB"], "w", encoding="utf-8") as fh:
        json.dump(web_document(web_rows, web_envelope), fh, ensure_ascii=False, indent=1)
    truth["files"] = dict(NAMES, sapEnvelope=sap_envelope, webEnvelope=web_envelope)
    with open(os.path.join(out_dir, TRUTH_FILE), "w", encoding="utf-8") as fh:
        json.dump(truth, fh, ensure_ascii=False, indent=1)
    return paths, truth


def describe(truth):
    """The numbers of the ground truth, one line each (for --dry-run and the report)."""
    s = truth["summary"]
    return ["rows: SAP %(SAP)d, Salesforce %(SALESFORCE)d, web %(WEB)d" % s["rows"],
            "%d companies; in at least two sources: %d (%.1f %%); in all three: %d (%.1f %%); only on the website (prospects): %d" % (
                s["companies"], s["inAtLeastTwoSources"], s["inAtLeastTwoSourcesPercent"], s["inAllThreeSources"], s["inAllThreePercent"], s["onlyOnTheWebsite"]),
            "groups: " + ", ".join("%s %d" % (g, k) for g, k in s["groups"].items()),
            "pure duplicates inside a source: Salesforce %d, web %d; Salesforce rows linked to SAP by SAP_BP__c: %d" % (
                s["salesforceDuplicateCompanies"], s["webDuplicateCompanies"], s["salesforceRowsLinkedToSap"]),
            "SAP: %d addresses in all, %d legal names cut at 40 characters; Salesforce: %d SIRET, %d VAT; web SIRENs: %s" % (
                s["sapAddresses"], s["sapLongNames"], s["salesforceRowsWithSiret"], s["salesforceRowsWithVat"],
                ", ".join("%s %d" % kv for kv in sorted(s["webSirens"].items())))]


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        sys.stdout.write(__doc__)
        return 0 if argv else 2
    out = argv[0]
    sap_env, web_env = "odata", "submissions"
    i = 1
    while i < len(argv):
        if argv[i] == "--sap-envelope" and i + 1 < len(argv) and argv[i + 1] in ("odata", "results", "array"):
            sap_env = argv[i + 1]
            i += 1
        elif argv[i] == "--web-envelope" and i + 1 < len(argv) and argv[i + 1] in ("submissions", "array"):
            web_env = argv[i + 1]
            i += 1
        else:
            sys.stderr.write("unexpected argument: %s\n" % argv[i])
            return 2
        i += 1
    paths, truth = write(out, sap_env, web_env)
    for line in describe(truth):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
