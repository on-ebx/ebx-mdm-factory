"""Three fictional B2C customer files for an MDM Factory landing test: web shop (JSON), store tickets (CSV, no customer id),
loyalty members (CSV). The same people appear in several files with small variations, so a later matching step has work to do."""
import csv, json, random, sys, os

random.seed(20261017)
out = sys.argv[1]
os.makedirs(out, exist_ok=True)

FIRST = ["Camille", "Louise", "Léa", "Manon", "Chloé", "Inès", "Jade", "Emma", "Sarah", "Julie", "Lucas", "Hugo", "Louis", "Gabriel",
	"Arthur", "Jules", "Nathan", "Thomas", "Théo", "Paul", "Hélène", "Céline", "Nicolas", "Antoine", "Mathilde", "Élodie", "Bastien", "Claire"]
LAST = ["Martin", "Bernard", "Dubois", "Thomas", "Robert", "Richard", "Petit", "Durand", "Leroy", "Moreau", "Simon", "Laurent", "Lefèvre",
	"Michel", "Garcia", "David", "Bertrand", "Roux", "Vincent", "Fournier", "Morel", "Girard", "André", "Mercier", "Dupont", "Lambert", "Bonnet"]
CITIES = [("Paris", "75011"), ("Lyon", "69003"), ("Marseille", "13006"), ("Bordeaux", "33000"), ("Lille", "59000"), ("Nantes", "44000"),
	("Toulouse", "31000"), ("Rennes", "35000"), ("Strasbourg", "67000"), ("Montpellier", "34000")]
STREETS = ["rue de la République", "avenue Jean Jaurès", "boulevard Voltaire", "rue Victor Hugo", "place du Marché", "rue des Lilas",
	"allée des Tilleuls", "rue Pasteur", "avenue de la Gare", "rue du Commerce"]
STORES = ["PAR-BASTILLE", "LYO-PARTDIEU", "MRS-PRADO", "BDX-STECATHERINE", "LIL-EURALILLE", "NTS-ATLANTIS"]

def strip(s):
	return s.lower().replace("é", "e").replace("è", "e").replace("ë", "e").replace("ô", "o").replace("ç", "c").replace("É", "e").replace(" ", "")

people = []
truth = []     # (master scenario, tools/e2e/b2c_master.sh) which person every source row was drawn from: {"app", "key", "person" = index in `people`}
for i in range(220):
	f, l = random.choice(FIRST), random.choice(LAST)
	city, cp = random.choice(CITIES)
	people.append({
		"first": f, "last": l, "email": f"{strip(f)}.{strip(l)}{random.randint(1, 99)}@{random.choice(['gmail.com', 'orange.fr', 'free.fr', 'outlook.fr'])}",
		"phone": "06" + "".join(str(random.randint(0, 9)) for _ in range(8)),
		"street": f"{random.randint(1, 120)} {random.choice(STREETS)}", "city": city, "cp": cp,
		"birth": f"{random.randint(1955, 2004)}-{random.randint(1, 12):02d}-{random.randint(1, 28):02d}",
		"idx": i,
	})

def fmt_phone(p, style):
	return p if style == 0 else ("+33 " + p[1] + " " + " ".join(p[i:i + 2] for i in range(2, 10, 2)) if style == 1 else ".".join(p[i:i + 2] for i in range(0, 10, 2)))

# 1. web shop: 160 customers, JSON envelope {"customers": [...]}
web = []
for n, p in enumerate(random.sample(people, 160)):
	web.append({"customerId": f"WEB-{100000 + n}", "firstName": p["first"], "lastName": p["last"], "email": p["email"],
		"mobile": fmt_phone(p["phone"], 1), "birthDate": p["birth"], "street": p["street"], "postalCode": p["cp"], "city": p["city"],
		"country": "FR", "newsletter": random.choice([True, False]), "createdAt": f"2025-{random.randint(1, 12):02d}-{random.randint(1, 28):02d}T10:{random.randint(10, 59)}:00Z",
		"updatedAt": f"2026-{random.randint(1, 9):02d}-{random.randint(1, 28):02d}T09:{random.randint(10, 59)}:00Z"})
	truth.append({"app": "ECOM", "key": web[-1]["customerId"], "person": p["idx"]})
with open(os.path.join(out, "ECOM_Clients.json"), "w", encoding="utf-8") as fh:
	json.dump({"customers": web}, fh, ensure_ascii=False, indent=1)

# 2. store tickets: one line per purchase, NO customer id (name, phone, email sometimes), several tickets per person
tickets = []
for p in random.sample(people, 120):
	for _ in range(random.randint(1, 3)):
		tickets.append({"NUM_TICKET": f"T{random.randint(10 ** 7, 10 ** 8 - 1)}", "MAGASIN": random.choice(STORES),
			"DATE_ACHAT": f"2026-{random.randint(1, 9):02d}-{random.randint(1, 28):02d}", "NOM": p["last"].upper(), "PRENOM": p["first"],
			"TELEPHONE": fmt_phone(p["phone"], 2), "EMAIL": p["email"] if random.random() < 0.6 else "", "CODE_POSTAL": p["cp"],
			"MONTANT_TTC": f"{random.uniform(9, 380):.2f}".replace(".", ",")})
		truth.append({"app": "STORES", "key": tickets[-1]["NUM_TICKET"], "person": p["idx"]})
with open(os.path.join(out, "Magasins_Ventes.csv"), "w", encoding="utf-8", newline="") as fh:
	w = csv.DictWriter(fh, fieldnames=list(tickets[0].keys()), delimiter=";")
	w.writeheader()
	w.writerows(tickets)

# 3. loyalty members: card number key, address split differently
members = []
for p in random.sample(people, 140):
	members.append({"card_number": f"FID{random.randint(10 ** 9, 10 ** 10 - 1)}", "civilite": random.choice(["M.", "Mme"]),
		"nom": p["last"], "prenom": p["first"], "date_naissance": "/".join(reversed(p["birth"].split("-"))), "email": p["email"].upper() if random.random() < 0.2 else p["email"],
		"telephone": fmt_phone(p["phone"], 0), "adresse": p["street"], "cp": p["cp"], "ville": p["city"].upper(),
		"niveau": random.choice(["Bronze", "Argent", "Or"]), "points": random.randint(0, 4800), "date_adhesion": f"20{random.randint(18, 26)}-{random.randint(1, 12):02d}-01"})
	truth.append({"app": "LOYALTY", "key": members[-1]["card_number"], "person": p["idx"]})
with open(os.path.join(out, "Fidelite_Membres.csv"), "w", encoding="utf-8", newline="") as fh:
	w = csv.DictWriter(fh, fieldnames=list(members[0].keys()), delimiter=",")
	w.writeheader()
	w.writerows(members)

print(len(web), "web customers,", len(tickets), "store tickets,", len(members), "loyalty members")

# 4. (hard-cases scenario, tools/e2e/b2c_hard.sh) a CRM contacts export about the SAME people, written with OTHER codes for the same concepts: civility MR / MRS / MISS
# (the loyalty file has M. / Mme), loyalty tier L1 / L2 / L3 (the loyalty file has Bronze / Argent / Or), a birth date written yyyyMMdd, and a free-text "comment"
# column whose every 12th value is far longer than any sensible maximum length (>= 600 characters). Generated AFTER the three files above, from its own seed, so the three
# files are byte for byte what they always were; the nominal scenario ignores this fourth file.
random.seed(20261018)
MALE = {"Lucas", "Hugo", "Louis", "Gabriel", "Arthur", "Jules", "Nathan", "Thomas", "Théo", "Paul", "Nicolas", "Antoine", "Bastien"}
LONG_COMMENT = ("Contact très apprécié des équipes, suivi personnalisé demandé lors du dernier rendez-vous en boutique ; préfère être joint par e-mail le matin, "
	"souhaite recevoir les invitations aux ventes privées et ne pas être sollicité par téléphone. Historique des échanges : ")
contacts = []
for i, p in enumerate(random.sample(people, 150)):
	if i % 12 == 0:
		comment = (LONG_COMMENT * 6)[:600 + (i % 5) * 10]
	elif i % 5 == 0:
		comment = ""
	else:
		comment = random.choice(["RAS", "Appeler après 18h", "Client fidèle depuis 2019", "A demandé un duplicata de carte", "Préfère le courrier"])
	contacts.append({"contact_id": f"CRM-{200000 + i}", "civility": "MR" if p["first"] in MALE else random.choice(["MRS", "MISS"]),
		"last_name": p["last"], "first_name": p["first"], "birth": p["birth"].replace("-", ""), "email": p["email"],
		"phone": fmt_phone(p["phone"], random.choice([0, 1])), "tier": random.choice(["L1", "L2", "L3"]), "comment": comment,
		"created_on": f"20{random.randint(18, 26)}-{random.randint(1, 12):02d}-{random.randint(1, 28):02d}"})
	truth.append({"app": "CRM", "key": contacts[-1]["contact_id"], "person": p["idx"]})
with open(os.path.join(out, "CRM_Contacts.csv"), "w", encoding="utf-8", newline="") as fh:
	w = csv.DictWriter(fh, fieldnames=list(contacts[0].keys()), delimiter=",")
	w.writeheader()
	w.writerows(contacts)
print(len(contacts), "CRM contacts,", sum(1 for c in contacts if len(c["comment"]) > 255), "with a comment longer than 255 characters")

# 5. (master scenario, tools/e2e/b2c_master.sh) the ground truth: for every source row of the four files, the person it came from. Written last, from what the loops above
# recorded, without drawing a single random number: the four files are byte for byte what they always were. Two rows with the same person are a true duplicate pair, within
# a source (several tickets of one person) or across sources.
with open(os.path.join(out, "truth_b2c.json"), "w", encoding="utf-8") as fh:
	json.dump({"people": len(people), "rows": truth}, fh, ensure_ascii=False, indent=0)
print(len(truth), "rows in truth_b2c.json for", len(set(t["person"] for t in truth)), "distinct people")
