"""A stand-in MDM Factory for the B2C scenario's self-test (lot H2): an in-memory fake of the REST routes and the Data Services tables the scenario uses, with a
happy path modelled on the live retest of 2026-10-17 (project TiersB2CRetestFinal) and switchable defects, so every assertion of the scenario is shown to pass
on a good run and to fail on the defect it guards. It is NOT a model of EBX: it answers what the scenario asks, in the shapes read from the live instance.

Defects (a set of names given to FakeFactory): "isodate" (the loyalty birth date read as ISO), "plusdrop" (a step strips the + of phones), "orphan" (a rule entity
left PROPOSED), "noe164" (no E.164 attribute), "phonegap" (lot M15: the phone column of the STORES tickets is mapped only to a superseded entity, so no STORES row of Client has a phone), "namelib" (lot M11: the name key is a SCRIPT derivation of the names library whose attribute has an unrelated name and no source - found by its library only), "nonamekey" (no name key at all), "reject" (a TransferReject row), "lowload" (the web source loads one row), "resume" (a second job was needed to finish the landing).

HARD MODE (FakeFactory(hard=True), tools/e2e/b2c_hard.sh): a 4th source CRM, code tables on the mapping steps, the preflight routes, a child entity under the loyalty and web
feeds, the load with / without "build anyway", rejects with reason codes / categories, the causes route, the connections the upload creates. Its own defects: "nocodes" (no
transcoding proposal), "codegap" (a table leaves a code without target, not flagged), "misaligned" (the loyalty tier is mapped to a list the CRM codes are not in), "nochild"
(no child entity is built: the cascade is not exercised), "orphans" (the children of a rejected parent are written, not rejected), "pfblind" (the preflight never blocks),
"pfsilent" (the preflight does not see the long comments), "nofix" (apply=* applies nothing), "stuck" (rejects are never resolved by the rebuild), "toolongloaded" (long
comments are loaded), "bannerrule" (the banner blames a rule), "crmiso" (the CRM birth date is read as ISO), "nobuildanyway" (the parameter is ignored), "keepflags"
(a code table flagged at 59 with its reason: NOT a defect, the tolerated shape), "delconn" (the project deletion also removes the connections: lot C landed).

MASTER MODE (FakeFactory(master=True), tools/e2e/b2c_master.sh): the one-click master build, the matching plan (proposed, accepted, simulated, configured), the add-on's activation read on
the match-merge MCP endpoint, the matching run that clusters the master person records and writes the metadataset (RecordMetadata, Suspected), the Xref rows. Matching is a stand-in:
records are grouped by the last 9 digits of the E.164 phone, which is the same person in the generated files. Its defects: "mm_noplan" (no plan is proposed), "mm_previewblocks" (the
live defect of 2026-10-17: Configure refuses once the plan was simulated), "mm_previewleft" (the preview policy stays after the configure), "mm_nometadataset" (state ENABLED but no
metadataset), "mm_masterrows" (the master build drops 5 rows without a reject), "mm_masterreject" (a MASTER reject, a row not copied), "mm_falsemerge" (records are grouped by last name:
two people become one), "mm_nomatch" (nothing is merged or suspected), "mm_suspectonly" (NOT a defect: the plan has autoMerge off, every duplicate is a suspect), "mm_runfails" (the run
job fails), "mm_xrefdangling" (an Xref row points to no master record), "mm_unknownsource" (a master row carries a source that is not in the truth), "mm_sourceoverwritten" (the
run turns the sourceSystem of every master record into MDM: lot M1, 2026-10-17), "mm_nomemberpolicy" (a member table of the business object without a default matching policy
and a merge policy: lot M4, 2026-10-18), "mm_membermode" (the member's merge policy in another mode than the main table's, Adresse DISABLED against Client ONLY_DUPLICATES: the
live refusal of 2026-10-18 02:57, lot M8), and for lot M10b "mm_automergeoff" (NOT a defect, the live state: a MATCH leaf but autoMerge false, so nothing merges until the option --auto-merge writes
MatchPlan.autoMerge), "mm_automergeignored" (the write is accepted but the run never merges), "mm_suspectchain" (NOT a defect: one false suspect pair chains two people; no false merge),
for lot M18 "mm_boleft" (the project deletion leaves the add-on's business-object and table configuration rows of the project's publication: the cleanup check must FAIL) and "noorphanroute" (GET /matching/orphans is missing: the check is skipped, never passed), and for lot M11 "mm_goldennosource" (NOT a defect, the older shape: the auto-created golden carries no source; by default it carries the source "MDM" and a generated key, as on the live instance).

B2B MODE (FakeFactory(b2b=True), tools/e2e/b2b_scenario.sh): the same routes and tables, but the files are the SAP / Salesforce / website ones and what the factory proposes and loads is an organisation
entity with its address child (propose_b2b / load_b2b -> b2b_standin.py, which lists its own defects).

DISTRIBUTION (lot S1, master mode, step 10 and 11 of the master scenario): a Kafka broker in memory (FakeKafka: reader / probe are what the scenario's `topic_reader` and `broker_probe`
take), the Connectors dataset (Connector, ConnectorProperty), the distribution routes (connections, targets, configure, install-triggers, first-full-send, stop, disarm, events), the
add-on's acceptSuspects REST, the DistributionFlow items of the deletion plan, PipelineStep rows and the Linked_Field_State column on the person master rows. The good behaviour sends the
golden records only (merged members and unmerged goldens are left out), one topic per table named after the project, key = id, a real-time message on a change, a message for the
golden a merge creates and, since lot S2 (2026-10-18), a RETIRE notice for each merged member. Every message carries `"_change"` (lot S2: UPSERT, RETIRE, DELETE), the reader prints the CLI's own
notice line first like kafka-console-consumer.sh 4.x does (the scenario must not count it), and the unmerge route of the add-on (DELETE mergingProcesses/{dataspace}/{dataset}/root/<Table>/<golden>)
retires the deleted golden (reason UNMERGE) and re-sends the members that are GOLDEN again. NOT defects: "ds_noretire" (the gap measured 2026-09-27, before lot S2: no retire notice for a merged
member), "ds_nochange" (a build before lot S2: no `_change`, the retire notice recognised by `retired: true`), "ds_unmergebare" (the add-on wants the dataspace without its B prefix),
"ds_unmergedouble" (it wants the double-slash table path), "mm_unmergepending" (the members come back PENDING_APPROVAL, nothing to re-send), "ds_unmergenoretire" (the unmerge sends the members
but no retire notice for the golden), "mm_membercopies" (the cascade merge gives the member table golden copies and leaves MERGED originals). Its defects: "ds_unmergerefused" (the unmerge is
refused, 500), "ds_unmergenoop" (200 but nothing is unmerged), "ds_unmergesilent" (the unmerge works and NOTHING is sent), "ds_livemerged" (a record message for a MERGED member is sent with the
real-time write: the live-only guard), "ds_mergedpub" (the merged members are published too, in the first send; for a member table too), "ds_missinggolden" (the
first golden record is not sent), "ds_dupmsg" (the first record is sent twice), "ds_wrongkey" (the key is the source key, not the id), "ds_onetopic" (every table to one topic), "ds_rtmissed" (a
change on a golden record is never sent), "ds_rtdouble" (it is sent twice), "ds_brokerdown" (no broker answers), "ds_sendfails" (the first full send fails), "ds_notarmed" (the send is
DONE but the target is not armed), "ds_configfails", "ds_notriggers", "ds_noconnection" (the new connection is not offered as a candidate), "ds_nogoldenmsg" (the golden a merge creates is
never sent), "ds_acceptnomerge" (the add-on accepts the suspect without merging), "ds_acceptnoB" (the add-on refuses a
dataspace written with its B prefix), "ds_acceptrefused" (always 500), "ds_targetsleft" (the project deletion leaves the target rows: a Factory gap, the scenario deletes them), "ds_flowsleft"
(the deletion leaves the mappings, so the connection cannot be deleted), "ds_nojournal" (no MASTER_LOAD step is ever written), "nodistroutes" (the distribution routes are missing).
"""
import base64
import csv
import io
import json
import os
import re
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2e_lib as L  # noqa: E402

BASE = "/ebx-mdm-factory/rest/v1"
UNMERGE_ROUTE = re.compile(r"^/ebx-addon-mame/rest/v1/mergingProcesses/([^/]+)/([^/]+)/(.*)$")
DS_ROOT = re.compile(r"^/ebx-dataservices/rest/data/v1/B([^/]+)/([^/]+)/root/([^/]+)(?:/(.+))?$")


class FakeKafka(object):
    """A broker in memory: topic -> [{key, value}]. `reader(topic, idle_ms)` and `probe()` have the signatures the scenario's injection points take. The reader goes through the SAME text
    parser as the real CLI reader: it builds the stdout of `kafka-console-consumer.sh` (the CLI 4.x deprecation notice first, as measured live on 2026-10-18, then `key<TAB>value` lines)
    and parses it, so a notice line counted as a message would show in the scenario."""

    CLI_NOTICE = "Option --property is deprecated and will be removed in a future version. Use --formatter-property instead."

    def __init__(self, down=False):
        self.topics = {}
        self.down = down
        self.reads = 0
        self.deleted = []             # the topics a delete was asked for, in order
        self.refuse_delete = set()    # topics whose deletion the broker refuses

    def publish(self, topic, key, doc):
        self.topics.setdefault(topic, []).append({"key": str(key), "value": json.dumps(doc, sort_keys=True)})

    def reader(self, topic, idle_ms=None):
        self.reads += 1
        if self.down:
            return [], "the broker could not be reached: Connection refused"
        stdout = self.CLI_NOTICE + "\n" + "".join("%s\t%s\n" % (m["key"], m["value"]) for m in self.topics.get(topic, []))
        return L.parse_console_output(stdout), None

    def list_topics(self):
        """lister(): what `kafka-topics.sh --list` gives (lot F4), plus a foreign topic that must never be touched."""
        if self.down:
            return [], "kafka-topics.sh --list failed at localhost:9082: Connection refused"
        return sorted(self.topics), None

    def delete_topic(self, topic):
        """deleter(topic): `kafka-topics.sh --delete --topic`; records the calls so a test sees exactly which names were asked for."""
        self.deleted.append(topic)
        if self.down:
            return False, "Connection refused"
        if topic in self.refuse_delete or "*" in self.refuse_delete:
            return False, "TopicDeletionDisabledException"
        self.topics.pop(topic, None)
        return True, ""

    def probe(self):
        if self.down:
            return False, "no Kafka broker answers at localhost:9082 ([Errno 61] Connection refused)"
        return True, "localhost:9082 answers; %d topic(s) listed" % len(self.topics)


def numeric_id(fields):
    """Lot M16: the real instance answers an auto-increment `id` as a NUMBER ({"content": 207}) and a foreign key to it as a string ("207"); the stand-in kept every id a string, which hid
    a lookup by text(id) in a dict keyed by the number (the phone-per-source check ended SKIP on the real instance). Only the row's own `id` of the factory tables is turned back into a number."""
    ident = fields.get("id")
    if isinstance(ident, str) and ident.isdigit():
        fields = dict(fields)
        fields["id"] = int(ident)
    return fields


def wrap(fields):
    out = {}
    for k, v in fields.items():
        if isinstance(v, list):
            out[k] = {"content": [{"content": x} for x in v]}
        else:
            out[k] = {"content": v}
    return {"content": out}


def evaluate(expr, row):
    """./field='value' comparisons joined by and / or / parentheses."""
    tokens = re.findall(r"\(|\)|\band\b|\bor\b|\./[A-Za-z_]+\s*=\s*'(?:[^']|'')*'", expr)
    pos = [0]

    def peek():
        return tokens[pos[0]] if pos[0] < len(tokens) else None

    def take():
        pos[0] += 1
        return tokens[pos[0] - 1]

    def expression():
        v = term()
        while peek() == "or":
            take()
            r = term()
            v = v or r
        return v

    def term():
        v = factor()
        while peek() == "and":
            take()
            r = factor()
            v = v and r
        return v

    def factor():
        t = take()
        if t == "(":
            v = expression()
            take()
            return v
        m = re.match(r"\./([A-Za-z_]+)\s*=\s*'((?:[^']|'')*)'", t)
        value = row.get(m.group(1))
        if isinstance(value, list):
            return m.group(2).replace("''", "'") in [str(x) for x in value]
        return ("" if value is None else str(value)) == m.group(2).replace("''", "'")
    return expression()


def text_of(v):
    return "" if v is None else str(v)


class FakeFactory:
    def __init__(self, defects=(), hard=False, master=False, b2b=False):
        self.defects = set(defects)
        self.hard = hard
        self.master = master
        self.b2b = b2b             # B2B mode (tools/e2e/b2b_scenario.sh): SAP / Salesforce / web files, an organisation entity and its address child (see b2b_standin.py)
        self.master_data = {}       # project code -> {table: [rows]}
        self.mds = {}               # project code -> {"RecordMetadata": [...], "Suspected": [...]} once the matching ran (or was configured)
        self.preview = set()        # project codes whose plan was simulated before its configuration
        self.configured = set()
        self.addon_rows = set()     # lot M18: project codes whose publication has rows in the add-on's configuration dataset (a business object and its table configurations)
        self.connections = {}
        self.last_preflight = None
        self.lock = threading.Lock()
        self.tables = {}
        self.next_id = 1000
        self.jobs = {}
        self.files = {}
        self.staging = {}
        self.settings = {"DEFAULT": {"code": "DEFAULT", "role": "SOURCE", "instanceId": "inst-1"}}
        self.log = []
        self.kafka = FakeKafka("ds_brokerdown" in self.defects)
        self.triggers = set()          # project codes whose master model got the distribution triggers
        self.dist_mappings = {}        # Connectors mapping id -> {connector, project, target}
        self.dist_events = []
        self.member_states = {}        # (project code, table, id) -> Linked_Field_State of a member table row (lot S4)

    # ------------------------------------------------------------------------------------------------------------------ tables
    def table(self, name):
        return self.tables.setdefault(name, {})

    def add(self, table, row):
        rid = str(row.get("id") if row.get("id") is not None else self.new_id())
        row = dict(row)
        row["id"] = rid
        self.table(table)[rid] = row
        return row

    def new_id(self):
        self.next_id += 1
        return self.next_id

    # ------------------------------------------------------------------------------------------------------------------ the project
    def import_files(self, code, files):
        out = []
        for i, f in enumerate(files):
            name = f["name"]
            m = re.match(r"^data:([^;,]+);name=([^;,]*);base64,(.*)$", f["dataUrl"], re.S)
            payload = base64.b64decode(m.group(3)).decode("utf-8")
            if name.endswith(".json"):
                rows = self.json_rows(payload)
                columns = list(rows[0].keys())
            else:
                delim = ";" if payload.split("\n", 1)[0].count(";") > payload.split("\n", 1)[0].count(",") else ","
                rows = list(csv.DictReader(io.StringIO(payload), delimiter=delim))
                columns = list(rows[0].keys())
            stem = name.rsplit(".", 1)[0]
            sid = self.add("ProjectSource", {"project": code, "sourceSystem": f["code"], "trustRank": f.get("trustRank"), "landingValidated": False})["id"]
            tid = self.add("LandingTable", {"projectSource": sid, "tableName": stem, "selected": True, "columnCount": len(columns), "status": "PROPOSED",
                                            "keyConfidence": 60, "inboundMapping": str(self.new_id())})["id"]
            self.files[tid] = rows
            for pos, c in enumerate(columns):
                suggested = ({"BusinessPartner": 1, "Name": 1, "submissionId": 1} if self.b2b else {"customerId": 1, "DATE_ACHAT": 1, "card_number": 1}).get(c, 0)
                self.add("LandingColumn", {"landingTable": tid, "sourceColumn": c, "fieldName": c, "sourceKey": bool(suggested), "sourceKeyOrigin": "RULE", "position": pos})
            connection = re.sub(r"[^A-Za-z0-9]", "", stem)
            self.connections[connection] = {"id": connection, "label": stem, "type": "sample-file", "active": True, "catalogCapable": True, "catalogSourceId": "cat" + sid, "project": code}
            out.append({"name": name, "connectionId": connection, "sourceId": "cat" + sid, "applicationAdded": True})
        return {"files": out}

    def json_rows(self, payload):
        """The rows of a JSON sample: the B2C envelope {"customers": [...]}, an OData v2 {"d": {"results": [...]}} or any single-array envelope, a bare array."""
        doc = json.loads(payload)
        if isinstance(doc, list):
            return doc
        if isinstance(doc.get("d"), dict):
            return doc["d"]["results"]
        return [v for v in doc.values() if isinstance(v, list)][0]

    def landing_run(self, code):
        for s in self.table("ProjectSource").values():
            if s["project"] != code:
                continue
            for t in self.table("LandingTable").values():
                if t["projectSource"] != s["id"]:
                    continue
                keys = sorted(c["sourceColumn"] for c in self.table("LandingColumn").values() if c["landingTable"] == t["id"] and c["sourceKey"])
                ok = bool(s.get("landingValidated")) and len(keys) == 1
                n = len(self.files[t["id"]])
                if "lowload" in self.defects and t["tableName"].startswith(("ECOM", "SAP_")):
                    n = 1
                t["status"] = "LOADED" if ok else "PROPOSED"
                t["lastLoadRows"] = n if ok else 0
                t["message"] = "Loaded %d row(s)." % n if ok else "waiting for a key"
        if "resume" in self.defects:
            self.job(code, "LANDING_RUN_RESUME", "A table waited for its key and was resumed.")
        self.step_row(code, "LANDING_LOAD")
        return self.job(code, "LANDING_RUN", "Landing ready")

    def step_row(self, code, step, status="DONE", scope=""):
        """A PipelineStep row (what the summary's journal reads): started and ended at fixed instants."""
        self.add("PipelineStep", {"project": code, "step": step, "scope": scope, "status": status, "startedAt": "2026-10-18T10:00:%02d" % (len(self.table("PipelineStep")) % 60),
                                  "endedAt": "2026-10-18T10:01:%02d" % (len(self.table("PipelineStep")) % 60)})

    def job(self, code, kind, message, status="DONE"):
        jid = str(self.new_id())
        self.add("FactoryJob", {"id": jid, "project": code, "kind": kind, "status": status, "message": message})
        self.jobs[jid] = 0
        return jid

    # ------------------------------------------------------------------------------------------------------------------ the proposal
    def propose_b2b(self, code):
        import b2b_standin
        b2b_standin.propose(self, code)

    def load_b2b(self, code):
        import b2b_standin
        b2b_standin.load(self, code)

    def propose(self, code):
        tables = dict((self.source_of(t)["sourceSystem"], t) for t in self.table("LandingTable").values() if self.source_of(t)["project"] == code)
        e = self.table("StagingEntity")
        for i, app in enumerate(("ECOM", "STORES", "LOYALTY")):
            self.add("StagingEntity", {"id": str(177 + i), "project": code, "name": app + "V5", "origin": "RULE", "confidence": 90, "rationale": "One entity per table.",
                                       "status": "PROPOSED" if ("orphan" in self.defects and app == "LOYALTY") else "SUPERSEDED", "memberEntities": []})
        if self.hard:
            self.add("StagingEntity", {"id": "181", "project": code, "name": "CRMV5", "origin": "RULE", "confidence": 90, "rationale": "One entity per table.", "status": "SUPERSEDED", "memberEntities": []})
        self.add("StagingEntity", {"id": "180", "project": code, "name": "Client", "origin": "LLM", "confidence": 91, "status": "PROPOSED",
                                   "memberEntities": ["177", "178", "179"] + (["181"] if self.hard else []), "rationale": "Persons."})
        self.add("StagingEntity", {"id": "182", "project": code, "name": "Achat", "origin": "LLM", "confidence": 88, "status": "PROPOSED", "memberEntities": ["178"], "rationale": "Tickets."})
        if "nofeed" in self.defects:
            # lot M7 (2026-10-18, E2EMASTER20261017235630): an entity the model proposed and fed from no landing column, already ACCEPTED by a person (the sweep
            # itself never takes one) - its attributes have no mapping; the load writes nothing into it and says so on the row (see load)
            self.add("StagingEntity", {"id": "185", "project": code, "name": "ContactEmail", "origin": "LLM", "confidence": 89, "status": "ACCEPTED", "memberEntities": [],
                                       "rationale": "An e-mail address is a means of contact distinct from the identity of the client."})
            self.add("StagingAttribute", {"entity": "185", "name": "email", "derivedFrom": "", "derivation": "", "origin": "LLM", "confidence": 96, "status": "ACCEPTED"})
        for app in ("ECOM", "STORES", "LOYALTY") + (("CRM",) if self.hard else ()):
            self.add("TransferFeed", {"entity": "180", "landingTable": tables[app]["id"], "status": "PROPOSED", "id": "fd" + app})
        attrs = [("firstName", None, None), ("lastName", None, None), ("mobile", None, None), ("birthDate", None, None)]
        if self.hard:
            attrs += [("civility", None, None), ("tier", None, None)] + ([] if "nocomment" in self.defects else [("comment", None, None)])
        ids = {}
        for n, (name, df, dv) in enumerate(attrs):
            ids[name] = self.add("StagingAttribute", {"entity": "180", "name": name, "derivedFrom": "", "derivation": "", "origin": "LLM", "confidence": 93, "status": "PROPOSED"})["id"]
        if "noe164" not in self.defects:
            ids["mobileE164"] = self.add("StagingAttribute", {"entity": "180", "name": "mobileE164", "derivedFrom": ids["mobile"], "derivation": "SCRIPT", "origin": "LLM",
                                                                "confidence": 92, "status": "PROPOSED"})["id"]
        if "comment" in ids:
            self.table("StagingAttribute")[ids["comment"]]["maxLength"] = 255
        if "namelib" in self.defects:
            # lot M11: a key computed by Fasttrack.lib.names (here the Factory's own rule, origin RULE) that no name-based heuristic can find: unrelated name, no derivedFrom
            ids["matchToken"] = self.add("StagingAttribute", {"entity": "180", "name": "matchToken", "derivedFrom": "", "derivation": "SCRIPT", "origin": "RULE",
                                                                "confidence": 85, "status": "PROPOSED"})["id"]
            self.add("CapabilitySuggestion", {"id": "229", "project": code, "entity": "180", "attribute": ids["matchToken"], "nature": "SCRIPT_DERIVATION", "status": "PROPOSED",
                                              "targetLibrary": "library/Fasttrack/lib/names", "targetFunction": "sortedKeyOf", "targetImportName": "Fasttrack.lib.names",
                                              "binding": '{"firstName":"firstName","lastName":"lastName"}'})
        elif "nonamekey" not in self.defects:
            ids["lastNameKey"] = self.add("StagingAttribute", {"entity": "180", "name": "lastNameKey", "derivedFrom": ids["lastName"], "derivation": "FOLD_KEY", "origin": "RULE",
                                                                 "confidence": 85, "status": "PROPOSED"})["id"]
        ids["buyerFirstName"] = self.add("StagingAttribute", {"entity": "182", "name": "buyerFirstName", "origin": "LLM", "confidence": 20, "status": "PROPOSED",
                                                                "rationale": "To check: the same source columns already feed Client, so this field is a duplicate on Achat (suggestion: reject it)."})["id"]
        cols = dict(((self.table("LandingTable")[c["landingTable"]]["tableName"][:4], c["sourceColumn"]), c["id"]) for c in self.table("LandingColumn").values())

        def col(prefix, name):
            return cols[(prefix, name)]
        wanted = [("firstName", "ECOM", "firstName", None), ("firstName", "Maga", "PRENOM", None), ("firstName", "Fide", "prenom", None),
                  ("lastName", "ECOM", "lastName", None), ("lastName", "Maga", "NOM", None), ("lastName", "Fide", "nom", None),
                  ("mobile", "ECOM", "mobile", None), ("mobile", "Maga", "TELEPHONE", None), ("mobile", "Fide", "telephone", None),
                  ("birthDate", "ECOM", "birthDate", "date(yyyy-MM-dd,yyyy-MM-dd)"),
                  ("birthDate", "Fide", "date_naissance", "date(yyyy-MM-dd,yyyy-MM-dd)" if "isodate" in self.defects else "trim|date(dd/MM/yyyy,yyyy-MM-dd)")]
        feed_of = {"ECOM": "fdECOM", "Maga": "fdSTORES", "Fide": "fdLOYALTY", "CRM_": "fdCRM"}
        if self.hard:
            crm_birth = "isodate()" if "crmiso" in self.defects else "date(yyyyMMdd,yyyy-MM-dd)"
            wanted += [("firstName", "CRM_", "first_name", None), ("lastName", "CRM_", "last_name", None), ("mobile", "CRM_", "phone", None), ("birthDate", "CRM_", "birth", crm_birth),
                       ("civility", "Fide", "civilite", None), ("civility", "CRM_", "civility", None), ("tier", "Fide", "niveau", None), ("tier", "CRM_", "tier", None)]
            if "comment" in ids:
                wanted.append(("comment", "CRM_", "comment", None))
        mids = {}
        for attr, pref, colname, pipeline in wanted:
            if "phonegap" in self.defects and (attr, pref) == ("mobile", "Maga"):
                continue   # lot M15: the ticket phone never reaches Client.mobile (it stays on the superseded per-source entity)
            rationale = "Mapped."
            if pipeline and "isodate" in self.defects and pref == "Fide":
                rationale += " || Value check [was 99]: 140 of 140 values do not convert."
            mid = self.add("AttributeMapping", {"attribute": ids[attr], "landingColumn": col(pref, colname), "feed": feed_of[pref], "pipeline": pipeline, "rationale": rationale,
                                                  "confidence": 95, "status": "PROPOSED"})["id"]
            mids[(attr, pref)] = mid
            if attr == "mobile":
                arg = " "
                kind = "STRIP_CHARS"
                if "plusdrop" in self.defects and pref == "Maga":
                    kind, arg = "NORMALISE_IDENTIFIER", ".+-"
                self.add("MappingStep", {"mapping": mid, "kind": kind, "argument": arg, "status": "PROPOSED"})
        if self.hard:
            self.propose_hard(code, tables, mids)
        self.add("CapabilitySuggestion", {"id": "226", "project": code, "entity": "180", "attribute": ids.get("mobileE164", ""), "nature": "SCRIPT_DERIVATION", "status": "PROPOSED",
                                          "binding": '{"value":"mobile","defaultCountry":{"toBeProvided":"which country"}}'})
        self.add("CapabilitySuggestion", {"id": "227", "project": code, "entity": "180", "attribute": ids["lastName"], "nature": "SCRIPT_CONSTRAINT", "status": "PROPOSED", "binding": "{}"})
        self.add("CapabilitySuggestion", {"id": "228", "project": code, "entity": "180", "attribute": ids["mobile"], "nature": "EXTERNAL_SERVICE", "status": "PROPOSED", "binding": "{}"})
        self.job(code, "STAGING_PROPOSAL", "Proposal run finished.")

    def propose_hard(self, code, tables, mids):
        """The transcoding proposals of the codes to align, and the child entity (addresses) under the loyalty and web feeds."""
        if "nocodes" not in self.defects:
            civility = {"M.": "MR", "Mme": "MRS"}
            tier = {"Bronze": "L1", "Argent": "L2", "Or": "L3"}
            targets_c, targets_t = ["MR", "MRS", "MISS"], ["L1", "L2", "L3"]
            confidence, rationale = 85, "The loyalty titles are mapped onto the common list."
            # staging last points (2026-10-17): a table that leaves a code without target lists the code with an EMPTY target (the shape the factory writes), and the
            # factory refuses to accept it (see accept_refusal)
            if "codegap" in self.defects:
                civility = {"M.": "MR", "Mme": ""}
            if "keepflags" in self.defects:
                civility, confidence, rationale = {"M.": "MR", "Mme": ""}, 59, "code table: 63 of 140 rows (1 distinct codes) have no target code."
            if "misaligned" in self.defects:
                tier, targets_t = {"Bronze": "BRONZE", "Argent": "SILVER", "Or": "GOLD"}, ["BRONZE", "SILVER", "GOLD"]
            for key, codes, targets, conf, why in ((("civility", "Fide"), civility, targets_c, confidence, rationale), (("tier", "Fide"), tier, targets_t, 85, "The loyalty levels are mapped onto the common list.")):
                table = {"codes": dict(codes), "targets": targets, "counts": dict((c, 10) for c in codes), "basedOn": ""}
                self.add("MappingStep", {"mapping": mids[key], "position": 1, "kind": "TRANSCODE_REFERENCE", "argument": json.dumps(table), "origin": "LLM", "confidence": conf,
                                         "rationale": why, "status": "PROPOSED"})
        if "nochild" not in self.defects:
            self.add("StagingEntity", {"id": "184", "project": code, "name": "ClientAddress", "origin": "LLM", "confidence": 90, "status": "PROPOSED", "memberEntities": [], "rationale": "Addresses."})
            for app in ("ECOM", "LOYALTY"):
                self.add("TransferFeed", {"entity": "184", "landingTable": tables[app]["id"], "status": "PROPOSED", "id": "fdADDR" + app, "parentFeed": "fd" + app})

    def source_of(self, t):
        return self.table("ProjectSource")[t["projectSource"]]

    def lines(self, code):
        out = []
        ents = {e["id"] for e in self.table("StagingEntity").values() if e["project"] == code}
        for e in self.table("StagingEntity").values():
            if e["project"] == code:
                out.append({"nature": "ENTITY", "id": e["id"], "element": e["name"], "proposition": "", "origin": e["origin"], "confidence": e["confidence"], "rationale": e.get("rationale", ""), "status": e["status"]})
        for a in self.table("StagingAttribute").values():
            if a["entity"] in ents:
                out.append({"nature": "ATTRIBUTE", "id": a["id"], "element": a["name"], "proposition": "", "origin": a["origin"], "confidence": a["confidence"], "rationale": a.get("rationale", ""), "status": a["status"]})
        for m in self.table("AttributeMapping").values():
            out.append({"nature": "MAPPING", "id": m["id"], "element": "m", "proposition": "", "origin": "LLM", "confidence": m["confidence"], "rationale": m.get("rationale", ""), "status": m["status"]})
        for st in self.table("MappingStep").values():
            out.append({"nature": "MAPPING_STEP", "id": st["id"], "element": "step", "proposition": "", "origin": st.get("origin", "RULE"), "confidence": st.get("confidence"),
                        "rationale": st.get("rationale", ""), "status": st["status"]})
        for c in self.table("CapabilitySuggestion").values():
            if c["project"] == code:
                out.append({"nature": c["nature"], "id": c["id"], "element": c["nature"], "proposition": "", "origin": "LLM", "confidence": 70, "rationale": "", "status": c["status"]})
        for pl in self.table("MatchPlan").values():
            if pl["entity"] in ents:
                out.append({"nature": "MATCH", "id": pl["id"], "element": "Client", "proposition": "", "origin": pl["origin"], "confidence": pl["confidence"], "rationale": "", "status": pl["status"]})
        return out

    def row_for(self, nature, rid):
        return {"ENTITY": self.table("StagingEntity"), "ATTRIBUTE": self.table("StagingAttribute"), "MAPPING": self.table("AttributeMapping"),
                "MAPPING_STEP": self.table("MappingStep"), "MATCH": self.table("MatchPlan")}.get(nature, self.table("CapabilitySuggestion")).get(rid)

    def accept_refusal(self, nature, row):
        """The factory's rule (ReviewRules.canAcceptMappingStep): a code table with an observed code that has rows and no target code is not accepted. None, or the English reason."""
        if nature != "MAPPING_STEP" or row is None or row.get("kind") != "TRANSCODE_REFERENCE":
            return None
        table = L.parse_code_table(row.get("argument") or "")
        gaps = [c for c, t in sorted(table["codes"].items()) if not t.strip()] if table else []
        return "Choose a target code for %s, then accept." % ", ".join(gaps) if gaps else None

    def bulk(self, code, accept, threshold):
        for ln in self.lines(code):
            row = self.row_for(ln["nature"], ln["id"])
            if row and row["status"] == "PROPOSED" and (row.get("confidence") or 0) >= threshold and ln["nature"] not in ("EXTERNAL_SERVICE",) \
                    and not row.get("rationale", "").startswith("To check:") and not (accept and self.accept_refusal(ln["nature"], row)):
                row["status"] = "ACCEPTED" if accept else "REJECTED"
        # the mapping steps follow the bar too (a step without a confidence follows its mapping)
        for s in self.table("MappingStep").values():
            if (s.get("confidence") is None or s["confidence"] >= threshold) and not (accept and self.accept_refusal("MAPPING_STEP", s)):
                s["status"] = "ACCEPTED"
        return self.job(code, "STAGING_BULK_DECIDE_RUN", "Accepted.")

    def generate(self, code):
        p = self.table("Project")[code]
        p["stagingDataspace"], p["stagingDataset"] = code + "_Staging", "Staging"
        if self.hard:
            self.staging[code] = {"Client": [], "ClientAddress": [], "Achat": []}
        return {"entities": 2}

    def load(self, code):
        rows = []
        by_table = dict((t["id"], t) for t in self.table("LandingTable").values())
        app_of = dict((t["id"], self.source_of(t)["sourceSystem"]) for t in by_table.values())
        mine = dict((tid, src) for tid, src in self.files.items() if self.source_of(by_table[tid])["project"] == code)
        for tid, src in mine.items():
            app = app_of[tid]
            for r in src:
                email = r.get("email") or r.get("EMAIL") or ""
                if app == "ECOM":
                    sid, first, last, phone, birth = r["customerId"], r["firstName"], r["lastName"], r["mobile"], r["birthDate"]
                elif app == "STORES":
                    sid, first, last, phone, birth = r["NUM_TICKET"], r["PRENOM"], r["NOM"], r["TELEPHONE"], ""
                    if "plusdrop" in self.defects:
                        pass
                else:
                    sid, first, last, phone, birth = r["card_number"], r["prenom"], r["nom"], r["telephone"], "-".join(reversed(r["date_naissance"].split("/")))
                    if "isodate" in self.defects:
                        birth = ""
                if app == "ECOM" and "plusdrop" in self.defects:
                    pass
                digits = re.sub(r"[^0-9]", "", phone)
                e164 = "+" + (digits if phone.startswith("+") else "33" + digits[1:])
                shown = phone.replace(" ", "") if phone.startswith("+") else phone
                if "phonegap" in self.defects and app == "STORES":
                    shown, e164 = "", ""
                if "plusdrop" in self.defects and app == "ECOM":
                    shown = digits
                rows.append({"sourceSystem": app, "sourceId": sid, "firstName": first, "lastName": last, "mobile": shown, "birthDate": birth, "mobileE164": e164,
                             "lastNameKey": last.lower(), "matchToken": (last + "|" + first).lower(), "email": email})
        achats = [{"sourceSystem": "STORES", "sourceId": r["NUM_TICKET"], "buyerLastName": r["NOM"]} for src in mine.values() for r in src if "NUM_TICKET" in r]
        self.staging[code] = {"Client": rows, "Achat": achats}
        if "nofeed" in self.defects:
            self.staging[code]["ContactEmail"] = []
        for e in self.table("StagingEntity").values():
            if e["project"] == code and e["status"] in ("ACCEPTED", "APPLIED"):
                n = len(self.staging[code].get(e["name"], []))
                e.update({"status": "APPLIED", "lastLoadWritten": n, "lastLoadRead": n, "lastLoadRejected": 0, "lastLoadUnchanged": 0, "lastLoadMessage": "%d written" % n})
                if e["name"] == "ContactEmail":
                    # like ebx.StagingLoader.NO_FEED_MESSAGE (lot M7): no accepted mapping on any attribute, nothing loaded, said on the row
                    e["lastLoadMessage"] = ("No feed: no accepted mapping feeds this entity, nothing was loaded into it. "
                                            "Map at least one attribute to a landing column and accept the mapping, or reject the entity.")
        self.apply_accepted_constraints(code)
        if "reject" in self.defects:
            self.add("TransferReject", {"project": code, "entity": "180", "sourceId": "x"})
        self.step_row(code, "STAGING_LOAD")
        self.job(code, "STAGING_LOAD_RUN", "Staging build finished.")



    def apply_accepted_constraints(self, code):
        # like ebx.ConstraintAutoApply (2026-10-17): the load applies the accepted STAGING-zone script constraints
        # lot M4 (2026-10-18, point C): "constraintleft" = a constraint left ACCEPTED with no word on the row (the defect); "constraintexplained" = left ACCEPTED because a parameter is
        # still "to be provided", and the row says so (applyMessage) - what the load does now
        for c in self.table("CapabilitySuggestion").values():
            if c.get("project") == code and c.get("nature") == "SCRIPT_CONSTRAINT" and c.get("status") == "ACCEPTED" and c.get("zone", "") in ("", "STAGING"):
                if "constraintleft" in self.defects:
                    continue
                if "constraintexplained" in self.defects:
                    c["applyMessage"] = "cannot apply '%s': parameter(s) [value] are still 'to be provided' in the binding - resolve them (reject and re-propose, or edit the row) before applying" % c["id"]
                    continue
                c["status"] = "APPLIED"

    # ------------------------------------------------------------------------------------------------------------------ master mode: master, matching
    APP_RANK = {"ECOM": 0, "LOYALTY": 1, "CRM": 2, "STORES": 3}

    def master_build(self, code):
        project = self.table("Project")[code]
        project["masterDataspace"], project["masterDataset"] = code + "_Master", "Master"
        data = {}
        staged = self.staging.get(code, {})
        for name in ("Client", "Achat"):
            rows = []
            for i, r in enumerate(staged.get(name, [])):
                row = dict(r)
                row["id"] = i + 1
                rows.append(row)
            data[name] = rows
        if "mm_masterrows" in self.defects:
            del data["Client"][-5:]
        if "mm_masterreject" in self.defects:
            lost = data["Client"].pop()
            self.add("TransferReject", {"project": code, "entity": "180", "stage": "MASTER", "sourceSystem": lost["sourceSystem"], "sourceId": lost["sourceId"],
                                         "category": "EBX_REFUSAL", "reasonCode": "tooLong|lastName|nom", "resolved": False})
        if "mm_unknownsource" in self.defects:
            data["Client"][0]["sourceId"] = "NOT-IN-THE-FILES"
        self.master_data[code] = data
        if "ds_nojournal" not in self.defects:
            self.step_row(code, "MASTER_LOAD")
        for r in data["Client"]:
            self.add("Xref", {"project": code, "stagingTable": "Client", "stagingKey": "%s|%s" % (r["sourceSystem"], r["sourceId"]), "sourceSystem": r["sourceSystem"], "sourceId": r["sourceId"],
                              "masterTable": "Client", "masterKey": str(r["id"]) if not ("mm_xrefdangling" in self.defects and r["id"] == 1) else "99999"})
        return self.job(code, "MASTER_BUILD_RUN", "2 business object(s), dataset %s_Master/Master." % code)

    def propose_matching(self, code):
        if "mm_noplan" not in self.defects:
            auto_off = "mm_suspectonly" in self.defects
            # "mm_automergeoff" (NOT a defect, the live state of 2026-10-18): the plan holds a MATCH leaf but autoMerge is false, so the MATCH leaf is configured as SUSPECTED and nothing merges
            # until the option --auto-merge writes MatchPlan.autoMerge
            nodes = [{"id": "N1", "function": "ALL_FIELDS", "onTrue": "SUSPECTED" if auto_off else "MATCH", "onFalse": "N2"},
                     {"id": "N2", "function": "AVERAGE", "onTrue": "SUSPECTED", "onFalse": "NOT_MATCHED"}]
            self.add("MatchPlan", {"entity": "180", "status": "PROPOSED", "origin": "LLM", "confidence": 82, "autoMerge": not (auto_off or "mm_automergeoff" in self.defects),
                                   "requirements": json.dumps({"decisionTree": {"root": "N1", "nodes": nodes}})})
        if "mm_childplan" in self.defects:
            # the defect of lot M2 (2026-10-17): a plan is proposed for a CHILD entity (an address with an accepted reference to the person)
            self.add("StagingEntity", {"id": "184", "project": code, "name": "ClientAddress", "origin": "LLM", "confidence": 90, "status": "ACCEPTED", "memberEntities": [], "rationale": "Addresses."})
            self.add("StagingAttribute", {"entity": "184", "name": "clientRef", "referenceEntity": "180", "status": "ACCEPTED", "origin": "LLM", "confidence": 90, "dataType": "STRING"})
            self.add("MatchPlan", {"entity": "184", "status": "PROPOSED", "origin": "LLM", "confidence": 80, "autoMerge": False,
                                   "requirements": json.dumps({"decisionTree": {"root": "N1", "nodes": [{"id": "N1", "function": "ALL_FIELDS", "onTrue": "SUSPECTED", "onFalse": "NOT_MATCHED"}]}})})
        return self.job(code, "MATCHING_SUGGEST_RULES", "1 matching plan written.")

    def configure_matching(self, code):
        applied = refused = 0
        for plan in self.table("MatchPlan").values():
            if plan["status"] != "ACCEPTED":
                continue
            if code in self.preview and "mm_previewblocks" in self.defects:
                refused += 1
                plan["message"] = "generate_merge_policy answered a soft miss: INVALID_PARAMS Merge policy Client__preview is already the manual-merge policy for this table."
                continue
            plan["status"] = "APPLIED"
            applied += 1
        self.last_code = code
        if applied:
            self.configured.add(code)
            self.addon_rows.add(code)
            if "mm_previewleft" not in self.defects and "mm_previewleftreported" not in self.defects:
                self.preview.discard(code)
        message = "%d entities configured, %d refused" % (applied, refused)
        if "mm_previewleftreported" in self.defects and code in self.preview:
            message += ". Client: preview policy Client__preview could not be removed (the table belongs to a business object)"

        return self.job(code, "MATCHING_CONFIGURE", message, "DONE_WITH_FAILURES" if refused else "DONE")

    def simulate_plan(self, code, plan_id):
        plan = self.table("MatchPlan").get(plan_id)
        if plan is None:
            return 404, {"error": "Matching plan %s is not a plan of project %s" % (plan_id, code)}
        on_preview = plan["status"] != "APPLIED"
        if on_preview:
            self.preview.add(code)
        return 200, {"entity": "Client", "plan": plan_id, "pivots": 18, "pairs": 59, "verdicts": {"SUSPECTED": 59}, "onPreviewPolicy": on_preview, "policyCode": "Client__preview" if on_preview else "Client"}

    def metadata_exists(self, code):
        return code in self.configured and "mm_nometadataset" not in self.defects

    def group_key(self, row):
        if "mm_falsemerge" in self.defects:
            return row["lastName"].lower()
        return re.sub(r"[^0-9]", "", row["mobileE164"])[-9:]

    def run_matching(self, code):
        if code not in self.configured:
            return self.job(code, "MATCHING_RUN", "No applied matching plan.", "FAILED")
        if "mm_runfails" in self.defects:
            return self.job(code, "MATCHING_RUN", "1 of 1 entities failed: Client - the add-on stopped.", "FAILED")
        clients = [r for r in self.master_data[code]["Client"] if r.get("sourceSystem")]
        auto = any(p["autoMerge"] for p in self.table("MatchPlan").values() if p["status"] == "APPLIED") and "mm_automergeignored" not in self.defects
        groups = {}
        for r in clients:
            groups.setdefault(self.group_key(r), []).append(r)
        meta, suspected = [], []
        next_id = max([r["id"] for r in self.master_data[code]["Client"]] + [0]) + 1
        gid = 1834926167335937
        for key in sorted(groups):
            members = sorted(groups[key], key=lambda r: (self.APP_RANK.get(r["sourceSystem"], 9), r["id"]))
            gid += 11
            if "mm_nomatch" in self.defects or len(members) == 1:
                for r in members:
                    meta.append({"functionalId": str(r["id"]), "groupId": 5000000000000000 + r["id"], "state": 1, "autoCreated": False, "goldenId": None, "hasSuspects": False})
                continue
            if auto:
                golden = dict(members[0])
                # live (2026-10-18): the add-on stamps the policy's autoCreateNewGoldenSource ("MDM") and a generated key on the golden it creates; "mm_goldennosource" = the older shape, none
                golden.update({"id": next_id, "sourceSystem": None if "mm_goldennosource" in self.defects else "MDM",
                               "sourceId": None if "mm_goldennosource" in self.defects else "FID%010d" % (5979509493 + next_id)})
                self.master_data[code]["Client"].append(golden)
                meta.append({"functionalId": str(next_id), "groupId": gid, "state": 1, "autoCreated": True, "goldenId": None, "hasSuspects": False})
                for r in members:
                    meta.append({"functionalId": str(r["id"]), "groupId": gid, "state": 2, "autoCreated": False, "goldenId": str(next_id), "hasSuspects": False})
                next_id += 1
            else:
                for n, r in enumerate(members):
                    meta.append({"functionalId": str(r["id"]), "groupId": 5000000000000000 + r["id"], "state": 1, "autoCreated": False, "goldenId": None, "hasSuspects": n == 0})
                for r in members[1:]:
                    suspected.append({"id": str(len(suspected) + 1), "sourceId": str(r["id"]), "targetId": str(members[0]["id"])})
        if "mm_suspectchain" in self.defects:
            # a few false suspect pairs (NOT a false merge): two records of two different people, both alone in their group, become suspects of each other
            alone = [groups[k][0] for k in sorted(groups) if len(groups[k]) == 1]
            if len(alone) >= 2:
                suspected.append({"id": str(len(suspected) + 1), "sourceId": str(alone[0]["id"]), "targetId": str(alone[1]["id"])})
        if "mm_sourceoverwritten" in self.defects:
            # the live defect of 2026-10-17 (lot M1): the add-on applied a constant survivor to records that were not merged - every source became MDM
            for r in clients:
                r["sourceSystem"] = "MDM"
        self.mds[code] = {"RecordMetadata": meta, "Suspected": suspected}
        if "mm_membercopies" in self.defects:
            # lot S4: the cascade merge gives the member table golden copies of its rows and leaves the originals MERGED (live: Adresse 1068 rows = 534 GOLDEN + 534 MERGED)
            achats = self.master_data[code].get("Achat", [])
            next_achat = max([r["id"] for r in achats] + [0]) + 1
            for r in list(achats[:2]):
                self.member_states[(code, "Achat", str(r["id"]))] = 2
                achats.append(dict(r, id=next_achat))
                next_achat += 1
        self.step_row(code, "MATCHING_RUN")
        return self.job(code, "MATCHING_RUN", "1 entity matched: %d groups." % len(groups))

    def mcp_call(self, body):
        call = json.loads(body)
        tool = call["params"]["name"]
        table = call["params"]["arguments"].get("table", "")
        code = getattr(self, "last_code", None)
        business_object = call["params"]["arguments"].get("businessObject", "")
        if tool == "get_business_object_config":
            # lot M4: the business object of the person table, its member /root/Adresse; "mm_nomemberpolicy" = the member holds no policy (the live state of 2026-10-18 that made the run refuse)
            if business_object != "/root/Client" or code is None or code not in self.configured:
                doc = {"success": False, "code": "CONFIG_NOT_FOUND", "message": "no member table has a configuration"}
            else:
                member = {"table": "/root/Adresse", "matchingPolicies": [], "mergePolicies": [], "tableSettings": {"defaultMatchingPolicy": None}}
                if "mm_nomemberpolicy" not in self.defects:
                    # lot M8: "mm_membermode" = the member's merge policy in another mode than the main table's (the live state of 2026-10-18 02:57: Adresse DISABLED, Client ONLY_DUPLICATES)
                    mode = "DISABLED" if "mm_membermode" in self.defects else "ONLY_DUPLICATES"
                    member = {"table": "/root/Adresse", "matchingPolicies": [{"processCode": "Adresse"}], "mergePolicies": [{"code": "Adresse", "mode": mode}],
                              "tableSettings": {"defaultMatchingPolicy": "Adresse"}}
                main = {"table": "/root/Client", "matchingPolicies": [{"processCode": "Client"}], "mergePolicies": [{"code": "Client", "mode": "ONLY_DUPLICATES"}],
                        "tableSettings": {"defaultMatchingPolicy": "Client"}}
                doc = {"success": True, "data": {"businessObject": {"id": "12", "mainTable": "/root/Client"}, "members": [main, member]}}
        elif table != "/root/Client" or code is None or code not in self.configured:
            doc = {"success": False, "code": "CONFIG_NOT_FOUND", "message": "no configuration"}
        elif tool == "get_matching_activation_status":
            doc = {"success": True, "data": {"state": "ENABLED", "published": True, "paused": False, "metadatasetExists": self.metadata_exists(code)}}
        else:
            codes = ["Client"] + (["Client__preview"] if code in self.preview else [])
            doc = {"success": True, "data": {"matchingPolicies": [{"processCode": c} for c in codes], "mergePolicies": [{"processCode": "Client"}]}}
        return {"jsonrpc": "2.0", "id": call.get("id"), "result": {"content": [{"type": "text", "text": json.dumps(doc)}]}}

    # ------------------------------------------------------------------------------------------------------------------ hard mode: preflight, load, causes
    SOURCE_COLUMNS = {
        "ECOM": {"id": "customerId", "firstName": "firstName", "lastName": "lastName", "mobile": "mobile", "birthDate": "birthDate"},
        "STORES": {"id": "NUM_TICKET", "firstName": "PRENOM", "lastName": "NOM", "mobile": "TELEPHONE"},
        "LOYALTY": {"id": "card_number", "firstName": "prenom", "lastName": "nom", "mobile": "telephone", "birthDate": "date_naissance", "civility": "civilite", "tier": "niveau"},
        "CRM": {"id": "contact_id", "firstName": "first_name", "lastName": "last_name", "mobile": "phone", "birthDate": "birth", "civility": "civility", "tier": "tier", "comment": "comment"}}
    BIRTH_FORMAT = {"ECOM": "yyyy-mm-dd", "LOYALTY": "dd/mm/yyyy", "CRM": "yyyymmdd"}
    BIRTH_FIX = {"ECOM": "trim|date(yyyy-MM-dd,yyyy-MM-dd)", "LOYALTY": "trim|date(dd/MM/yyyy,yyyy-MM-dd)", "CRM": "trim|date(yyyyMMdd,yyyy-MM-dd)"}
    CAUSE_ORDER = ["QUALITY_RULE", "EBX_REFUSAL", "DATA_CHECK", "COMPUTATION", "PARENT_REJECTED", "OTHER"]
    CAUSE_TEXT = {"EBX_REFUSAL": "refused by EBX, the data model does not accept the row", "PARENT_REJECTED": "the row belongs to a parent row that was set aside",
                  "DATA_CHECK": "a check of the loader failed", "QUALITY_RULE": "a quality rule stopped them", "COMPUTATION": "the mapping could not compute the row", "OTHER": "other reasons"}

    def person_mappings(self):
        """[(mapping, attribute, landing column, application)] of the ACCEPTED / APPLIED mappings of the person entity (180)."""
        out = []
        for m in self.table("AttributeMapping").values():
            attr = self.table("StagingAttribute").get(m["attribute"])
            if attr is None or attr["entity"] != "180" or m["status"] not in ("ACCEPTED", "APPLIED"):
                continue
            column = self.table("LandingColumn")[m["landingColumn"]]
            out.append((m, attr, column, self.source_of(self.table("LandingTable")[column["landingTable"]])["sourceSystem"]))
        return out

    def birth_ok(self, mapping, app):
        norm = re.sub(r"\s+", "", mapping.get("pipeline") or "").lower()
        return self.BIRTH_FORMAT[app] in norm or ("isodate()" in norm and app == "ECOM")

    def compute_preflight(self, code):
        findings = []
        mappings = self.person_mappings()
        for m, attr, column, app in mappings:
            name, low = column["sourceColumn"], column["sourceColumn"].lower()
            rows = self.files[column["landingTable"]]
            filled = [r[name] for r in rows if r.get(name)]
            issues, fix = [], None
            if low in ("birthdate", "date_naissance", "birth") and not self.birth_ok(m, app):
                issues.append({"kind": "CONVERSION", "blocking": True, "rows": len(filled), "of": len(filled), "atLeast": False, "detail": "the chain leaves a value the date field cannot store"})
                fix = {"pipeline": self.BIRTH_FIX[app], "rows": len(filled)}
            if low == "comment" and "pfsilent" not in self.defects and attr.get("maxLength"):
                n = sum(1 for v in filled if len(v) > int(attr["maxLength"]))
                if n:
                    issues.append({"kind": "TOO_LONG", "blocking": True, "rows": n, "of": len(filled), "atLeast": False, "detail": "%d of %d values are longer than the %s characters the field allows." % (n, len(filled), attr["maxLength"])})
            if issues:
                finding = {"mapping": m["id"], "entity": "Client", "attribute": attr["name"], "source": app, "table": self.table("LandingTable")[column["landingTable"]]["tableName"],
                           "column": name, "dataType": "DATE" if fix else "STRING", "pipeline": m.get("pipeline") or "", "written": "", "writtenKind": "", "issues": issues}
                if fix:
                    finding["fix"] = fix
                findings.append(finding)
        if "pfblind" in self.defects:
            findings = []
        blocking = [f for f in findings if any(i["blocking"] for i in f["issues"])]
        return {"exists": True, "version": 1, "project": code, "at": "2026-10-17T10:00:00", "linksChecked": len(mappings), "linksNotChecked": 0, "blocking": bool(blocking),
                "blockingRows": sum(i["rows"] for f in blocking for i in f["issues"] if i["blocking"]), "blockingLinks": len(blocking), "warnings": 0,
                "findings": findings, "derivationsWaiting": [], "notes": []}

    def apply_fix(self, which):
        applied = stale = missing = 0
        last = self.last_preflight
        if not last:
            return {"applied": 0, "stale": 0, "missing": 1}
        if which == "*":
            targets = [f for f in last["findings"] if f.get("fix") and any(i["blocking"] for i in f["issues"])]
        else:
            targets = [f for f in last["findings"] if f["mapping"] == which and f.get("fix")]
            if not targets:
                missing += 1
        for f in targets:
            row = self.table("AttributeMapping")[f["mapping"]]
            if (row.get("pipeline") or "") != f["pipeline"]:
                stale += 1
            elif "nofix" not in self.defects:
                row["pipeline"] = f["fix"]["pipeline"]
                applied += 1
        return {"applied": applied, "stale": stale, "missing": missing}

    def transcode(self, mapping_id, value):
        for st in self.table("MappingStep").values():
            if st["mapping"] == mapping_id and st["kind"] == "TRANSCODE_REFERENCE" and st["status"] in ("ACCEPTED", "APPLIED"):
                codes = dict((re.sub(r"[^0-9a-z]", "", k.lower()), v) for k, v in json.loads(st["argument"])["codes"].items() if v)
                return codes.get(re.sub(r"[^0-9a-z]", "", value.lower()), value)
        return value

    def set_reject(self, code, entity, app, sid, category, reason_code):
        key = "%s|%s|%s|STAGING" % (entity, app, sid)
        self.table("TransferReject")[key] = {"id": key, "project": code, "entity": entity, "stage": "STAGING", "sourceSystem": app, "sourceId": sid, "category": category,
                                             "reasonCode": reason_code, "reasons": "rejected: " + reason_code, "resolved": False}

    def clear_reject(self, entity, app, sid):
        row = self.table("TransferReject").get("%s|%s|%s|STAGING" % (entity, app, sid))
        if row is not None and "stuck" not in self.defects:
            row["resolved"] = True

    def load_hard(self, code, build_anyway):
        if "nobuildanyway" in self.defects:
            build_anyway = False
        if not build_anyway:
            report = self.compute_preflight(code)
            self.last_preflight = report
            if report["blocking"]:
                self.job(code, "STAGING_LOAD_RUN", "Nothing to do: the build was not started - %d row(s) on %d link(s) would be refused. See the check at the top of step 4."
                         % (report["blockingRows"], report["blockingLinks"]))
                return
        by = dict(((app, attr["name"]), (m, attr, column)) for m, attr, column, app in self.person_mappings())
        child = self.table("StagingEntity").get("184")
        child_live = child is not None and child["status"] in ("ACCEPTED", "APPLIED")
        persons, children, rejected = [], [], {"180": 0, "184": 0}
        for tid, src in self.files.items():
            app = self.source_of(self.table("LandingTable")[tid])["sourceSystem"]
            cols = self.SOURCE_COLUMNS[app]
            for r in src:
                sid = r[cols["id"]]
                reason = None
                row = {"sourceSystem": app, "sourceId": sid, "firstName": r[cols["firstName"]], "lastName": r[cols["lastName"]], "lastNameKey": r[cols["lastName"]].lower()}
                phone = r[cols["mobile"]]
                digits = re.sub(r"[^0-9]", "", phone)
                row["mobile"] = phone.replace(" ", "") if phone.startswith("+") else phone
                row["mobileE164"] = "+" + (digits if phone.startswith("+") else "33" + digits[1:])
                birth = ""
                if "birthDate" in cols and (app, "birthDate") in by:
                    mapping = by[(app, "birthDate")][0]
                    raw = r[cols["birthDate"]]
                    if self.birth_ok(mapping, app):
                        birth = "-".join(reversed(raw.split("/"))) if app == "LOYALTY" else (raw[:4] + "-" + raw[4:6] + "-" + raw[6:] if app == "CRM" else raw)
                    else:
                        reason = ("EBX_REFUSAL", "convert.date|birthDate|" + cols["birthDate"])
                row["birthDate"] = birth
                for concept in ("civility", "tier"):
                    if concept in cols and (app, concept) in by:
                        row[concept] = self.transcode(by[(app, concept)][0]["id"], r[cols[concept]])
                if "comment" in cols and (app, "comment") in by:
                    attr = by[(app, "comment")][1]
                    value = r[cols["comment"]]
                    if len(value) > int(attr.get("maxLength") or 10 ** 9) and "toolongloaded" not in self.defects and reason is None:
                        reason = ("EBX_REFUSAL", "tooLong|comment|comment")
                    row["comment"] = value
                if reason is not None:
                    self.set_reject(code, "180", app, sid, reason[0], reason[1])
                    rejected["180"] += 1
                else:
                    persons.append(row)
                    self.clear_reject("180", app, sid)
                if child_live and app in ("ECOM", "LOYALTY"):
                    if reason is not None and "orphans" not in self.defects:
                        self.set_reject(code, "184", app, sid, "PARENT_REJECTED", "parent|" + reason[1])
                        rejected["184"] += 1
                    else:
                        children.append({"sourceSystem": app, "sourceId": sid, "city": "x"})
                        self.clear_reject("184", app, sid)
        achats = [{"sourceSystem": "STORES", "sourceId": r["NUM_TICKET"], "buyerLastName": r["NOM"]} for src in self.files.values() for r in src if "NUM_TICKET" in r]
        self.staging[code] = {"Client": persons, "ClientAddress": children, "Achat": achats}
        for e in self.table("StagingEntity").values():
            if e["project"] == code and e["status"] in ("ACCEPTED", "APPLIED"):
                n = len(self.staging[code].get(e["name"], []))
                lost = rejected.get(e["id"], 0)
                e.update({"status": "APPLIED", "lastLoadWritten": n, "lastLoadRead": n + lost, "lastLoadRejected": lost, "lastLoadUnchanged": 0, "lastLoadMessage": "%d written" % n})
        for c in self.table("CapabilitySuggestion").values():
            if c.get("project") == code and c.get("nature") == "SCRIPT_CONSTRAINT" and c.get("status") == "ACCEPTED" and c.get("zone", "") in ("", "STAGING"):
                c["status"] = "APPLIED"
        self.job(code, "STAGING_LOAD_RUN", "Staging build finished: %d written, %d rejected." % (len(persons) + len(children), sum(rejected.values())))

    def causes(self, code, stage):
        names = dict((e["id"], e["name"]) for e in self.table("StagingEntity").values() if e["project"] == code)
        total, by_entity = {}, {}
        for r in self.table("TransferReject").values():
            if r["project"] != code or r["stage"] != stage or r.get("resolved"):
                continue
            total[r["category"]] = total.get(r["category"], 0) + 1
            per = by_entity.setdefault(names.get(r["entity"], r["entity"]), {})
            per[r["category"]] = per.get(r["category"], 0) + 1
        out = self.cause_node(total)
        out["stage"] = stage
        out["entities"] = dict((n, self.cause_node(c)) for n, c in by_entity.items())
        return out

    def cause_node(self, counts):
        total = sum(counts.values())
        kinds = [c for c in self.CAUSE_ORDER if counts.get(c)]
        parts = [self.CAUSE_TEXT[c] if len(kinds) == 1 else "%s (%d)" % (self.CAUSE_TEXT[c], counts[c]) for c in kinds]
        sentence = ""
        if total:
            sentence = "%d row%s set aside: %s." % (total, "" if total == 1 else "s", "; ".join(parts))
            if "bannerrule" in self.defects:
                sentence = "%d rows set aside - a configured rule stopped them." % total
        return {"total": total, "counts": dict((c, counts[c]) for c in kinds), "sentence": sentence, "causePhrase": "; ".join(parts), "blamesARule": "QUALITY_RULE" in kinds}

    # ------------------------------------------------------------------------------------------------------------------ distribution (lot S1)
    DIST_SUBROUTES = re.compile(r"^distribution/(connections|targets|events|targets/([^/]+)/(configure|install-triggers|first-full-send|stop|disarm|arm-override|send))$")

    def is_distribution_route(self, method, tail):
        m = self.DIST_SUBROUTES.match(tail)
        if m is None or "nodistroutes" in self.defects:
            return False
        sub = m.group(1)
        if sub in ("connections", "events"):
            return method == "GET"
        if sub == "targets":
            return method in ("GET", "POST")
        return method == "POST"

    def member_state(self, code, table, rid):
        return self.member_states.get((code, table, str(rid)), 1)

    def meta_state(self, code, rid):
        for m in self.mds.get(code, {}).get("RecordMetadata", []):
            if str(m["functionalId"]) == str(rid):
                return m["state"]
        return 1

    def message_doc(self, row):
        """A record message: the business fields and, since lot S2, `_change: UPSERT` (a build before the lot - "ds_nochange" - has none)."""
        doc = dict((k, v) for k, v in row.items() if k not in ("_loadId", "_receivedAt") and not k.startswith("Linked_Field"))
        if "ds_nochange" not in self.defects:
            doc["_change"] = "UPSERT"
        return doc

    def retire_doc(self, rid, golden, reason, merge_id):
        """A retire notice as lot S2 builds it: {_change, id, retired, retiredTo?, reason, mergeId, at}."""
        doc = {"id": int(rid), "retired": True, "reason": reason, "mergeId": merge_id, "at": "2026-10-18T10:05:00Z"}
        if golden is not None:
            doc["retiredTo"] = str(golden)
        if "ds_nochange" not in self.defects:
            doc["_change"] = "RETIRE"
        return doc

    def send_to(self, code, table, key, doc, change):
        """What the dispatcher does for one message of an armed target: publish it on the table's topic and log the event."""
        target = self.armed_target(code)
        dest = self.parsed(target["tableDestinations"]).get("/root/" + table) if target else None
        if dest is None:
            return
        self.kafka.publish(dest, key, doc)
        self.dist_events.append({"id": str(len(self.dist_events) + 1), "target": target["id"], "tablePath": "/root/" + table, "recordKey": str(key), "change": change, "status": "SENT", "attempts": 1, "message": ""})

    def connector_topic(self, connector):
        for p in self.table("ConnectorProperty").values():
            if p.get("connector") == connector and p.get("key") == "topic":
                return p.get("value")
        return None

    def distribution_route(self, method, code, tail, query, body):
        m = self.DIST_SUBROUTES.match(tail)
        sub = m.group(1)
        if sub == "connections":
            conns = [{"connector": "Kafka", "label": "Apache Kafka", "type": "kafka", "writeOperations": ["publish"], "readOperations": ["consume", "peek"], "unknownOperations": [],
                      "topic": "ebx.companies", "keyField": "lei", "hasWriteOperation": True}]
            if "ds_noconnection" not in self.defects:
                for c in self.table("Connector").values():
                    conns.append({"connector": c["id"], "label": c.get("label", c["id"]), "type": c.get("type", ""), "writeOperations": ["publish"], "readOperations": ["consume", "peek"],
                                  "unknownOperations": [], "topic": self.connector_topic(c["id"]) or "", "keyField": "id", "hasWriteOperation": True})
            return 200, {"connections": conns}
        if sub == "events":
            target = query.get("targetId", [""])[0]
            return 200, {"events": [e for e in self.dist_events if not target or e["target"] == target]}
        if sub == "targets":
            if method == "GET":
                return 200, {"targets": [dict(t) for t in self.table("DistributionTarget").values() if t["project"] == code]}
            fields = json.loads(body or "{}")
            if not fields.get("connector") or fields.get("connector") not in self.table("Connector") and fields.get("connector") != "Kafka":
                return 409, {"error": "Distribution target: connector is required"}
            row = self.add("DistributionTarget", {"project": code, "connector": fields["connector"], "operation": fields.get("operation", ""), "mode": fields.get("mode", "ON_DEMAND"),
                                                   "cron": "", "scope": "CHANGED", "state": "DRAFT", "connectorsMappingIds": "", "tableDestinations": "", "firstFullSendRunId": "",
                                                   "lastStatus": "", "lastMessage": "", "lastSendAt": "", "retireMode": fields.get("retireMode", "NOTICE"), "retireUndoneMerges": True,
                                                   "armedViaOverride": False, "approval": False, "includeCrosswalk": False, "includePending": False, "watchedFields": ""})
            return 200, {"status": "DONE", "id": row["id"]}
        tid, action = m.group(2), m.group(3)
        target = self.table("DistributionTarget").get(tid)
        if target is None or target["project"] != code:
            return 409, {"error": "Distribution target %s does not belong to this project" % tid}
        if action == "configure":
            return self.dist_configure(code, target)
        if action == "install-triggers":
            if "ds_notriggers" in self.defects:
                return 409, {"error": "Could not install distribution triggers: publish_data_model refused"}
            if query.get("modelName", [""])[0] != code + "_Master":
                return 409, {"error": "Data model not found: '%s'" % query.get("modelName", [""])[0]}
            if not target["connectorsMappingIds"]:
                return 409, {"error": "Distribution target %s has no configured mapping yet" % tid}
            self.triggers.add(code)
            return 200, {"status": "DONE", "matchingNotActiveHint": False, "detail": {}}
        if action == "first-full-send":
            return self.dist_first_send(code, target)
        if action == "stop":
            return 200, {"targetId": tid, "stopped": False, "jobsCancelled": [], "runsStopped": [], "message": "No send of target %s was running." % tid}
        if action == "disarm":
            target["state"] = "DISABLED"
            return 200, {"status": "DONE", "state": "DISABLED"}
        return 404, None

    def dist_configure(self, code, target):
        if "ds_configfails" in self.defects:
            return 409, {"error": "Could not configure distribution target %s: flows.put refused" % target["id"]}
        prefix = self.connector_topic(target["connector"]) or code.lower()
        maps, dests = {}, {}
        for table in sorted(self.master_data.get(code, {})):
            path = "/root/" + table
            mid = str(self.new_id())
            self.dist_mappings[mid] = {"connector": target["connector"], "project": code, "target": target["id"]}
            maps[path] = mid
            dests[path] = prefix + ".all" if "ds_onetopic" in self.defects else "%s.%s" % (prefix, table.lower())
        target["connectorsMappingIds"] = ",".join("%s=%s" % kv for kv in maps.items())
        target["tableDestinations"] = ",".join("%s=%s" % kv for kv in dests.items())
        return 200, {"status": "DONE", "connectorsMappingIds": target["connectorsMappingIds"], "tableDestinations": target["tableDestinations"], "detail": {}}

    def parsed(self, text):
        out = {}
        for part in text.split(","):
            if "=" in part:
                k, _s, v = part.partition("=")
                out[k] = v
        return out

    def dist_first_send(self, code, target):
        if code not in self.mds or not target["connectorsMappingIds"]:
            return 409, {"error": "Cannot start the first full send: the first matching run has not finished yet."}
        if "ds_sendfails" in self.defects:
            target["state"], target["lastStatus"], target["lastMessage"] = "WAITING", "FAILED", "connectors_sync: broker refused"
            return 202, {"started": True, "jobId": self.job(code, "DISTRIBUTION_FIRST_FULL_SEND", "First full send of target %s failed: broker refused" % target["id"], "FAILED")}
        sent = 0
        dests = self.parsed(target["tableDestinations"])
        for path in self.parsed(target["connectorsMappingIds"]):
            table = path.rsplit("/", 1)[-1]
            rows = self.master_data[code].get(table, [])
            skipped_one = False
            duplicated = False
            for r in rows:
                state = self.meta_state(code, r["id"]) if table == "Client" else self.member_state(code, table, r["id"])
                if state in (2, 4) and "ds_mergedpub" not in self.defects:
                    continue
                if table == "Client" and "ds_missinggolden" in self.defects and not skipped_one:
                    skipped_one = True
                    continue
                key = r.get("sourceId") if "ds_wrongkey" in self.defects else r["id"]
                self.kafka.publish(dests[path], key, self.message_doc(r))
                sent += 1
                if "ds_dupmsg" in self.defects and not duplicated:
                    duplicated = True
                    self.kafka.publish(dests[path], key, self.message_doc(r))
                    sent += 1
        target["state"] = "WAITING" if "ds_notarmed" in self.defects else "ARMED"
        target["firstFullSendRunId"] = "R20261018-100000-1,R20261018-100000-2"
        target["lastStatus"], target["lastSendAt"] = "DONE", "2026-10-18T10:01:00Z"
        target["lastMessage"] = "Sent %d row(s) across %d mapping(s), scope=all" % (sent, len(self.parsed(target["connectorsMappingIds"])))
        self.step_row(code, "DISTRIBUTION_FIRST_FULL_SEND")
        return 202, {"started": True, "jobId": self.job(code, "DISTRIBUTION_FIRST_FULL_SEND", "First full send of target %s: DONE - %s" % (target["id"], target["lastMessage"]))}

    def armed_target(self, code):
        for t in self.table("DistributionTarget").values():
            if t["project"] == code and t["state"] == "ARMED" and t["mode"] == "REALTIME" and code in self.triggers:
                return t
        return None

    def trigger_fired(self, code, table, row, change):
        """The afterCreate / afterModify trigger of an armed REALTIME target and the dispatcher, collapsed into one call: one event, one message."""
        target = self.armed_target(code)
        if target is None:
            return
        path = "/root/" + table
        dest = self.parsed(target["tableDestinations"]).get(path)
        if dest is None:
            return
        copies = 2 if "ds_rtdouble" in self.defects and change == "MODIFY" else 1
        for _ in range(copies):
            self.kafka.publish(dest, row["id"], self.message_doc(row))
        if "ds_livemerged" in self.defects and table == "Client":
            # the live-only defect: the write also sends a record message for a record Match and Merge holds as MERGED
            merged = [m for m in self.mds.get(code, {}).get("RecordMetadata", []) if m["state"] == 2]
            if merged:
                member = [r for r in self.master_data[code]["Client"] if str(r["id"]) == str(merged[0]["functionalId"])]
                if member:
                    self.kafka.publish(dest, member[0]["id"], self.message_doc(member[0]))
        self.dist_events.append({"id": str(len(self.dist_events) + 1), "target": target["id"], "tablePath": path, "recordKey": str(row["id"]), "change": change, "status": "SENT", "attempts": 1, "message": ""})

    def accept_suspects(self, body):
        req = json.loads(body)
        if "ds_acceptrefused" in self.defects:
            return 500, {"userMessage": "An unexpected error occurred."}
        space = req.get("dataspace", "")
        has_b = space.startswith("B")
        if ("ds_acceptnoB" in self.defects) == has_b:       # the add-on wants the B prefix (or, with the defect, refuses it)
            return 500, {"userMessage": "An unexpected error occurred."}
        bare = space[1:] if has_b else space
        code = bare[:-len("_Master")] if bare.endswith("_Master") else ""
        meta = self.mds.get(code, {}).get("RecordMetadata")
        if meta is None:
            return 404, {"userMessage": "non-existence group id"}
        target = [m for m in meta if m["groupId"] == req.get("groupId")]
        sources = [str(x) for x in req.get("recordPrimaryKeys") or []]
        pairs = self.mds[code]["Suspected"]
        hit = [p for p in pairs if target and p["targetId"] == target[0]["functionalId"] and p["sourceId"] in sources]
        if not hit:
            return 400, {"userMessage": "at least one of the given records is not a suspect of the group"}
        for p in hit:
            pairs.remove(p)
        if "ds_acceptnomerge" in self.defects:
            return 200, {}
        clients = self.master_data[code]["Client"]
        ids = [target[0]["functionalId"]] + sources
        base = [r for r in clients if str(r["id"]) == ids[0]][0]
        gid = max(r["id"] for r in clients) + 1
        golden = dict(base, id=gid, sourceSystem="MDM", sourceId="FID%010d" % (5979509493 + gid))
        clients.append(golden)
        group = target[0]["groupId"]
        for m in meta:
            if m["functionalId"] in ids:
                m.update({"state": 2, "goldenId": str(gid), "groupId": group, "hasSuspects": False})
        meta.append({"functionalId": str(gid), "groupId": group, "state": 1, "autoCreated": True, "goldenId": None, "hasSuspects": False})
        if "ds_nogoldenmsg" not in self.defects:
            self.trigger_fired(code, "Client", golden, "CREATE")
        if "ds_noretire" not in self.defects:
            for rid in ids:
                self.send_to(code, "Client", rid, self.retire_doc(rid, gid, "MERGE", 1), "RETIRE")
        return 200, {}

    def unmerge(self, space, dataset, tail):
        """The add-on's `unMerge` (DELETE mergingProcesses/{dataspace}/{dataset}/{pathSegments}, the last segment the golden's key) and what the Factory's state sweep then sends (lot S2): a RETIRE
        notice of the deleted golden (reason UNMERGE, no retiredTo) and the full record of each member that is GOLDEN again."""
        if "ds_unmergerefused" in self.defects:
            return 500, {"userMessage": "An unexpected error occurred."}
        has_b = space.startswith("B")
        if ("ds_unmergebare" in self.defects) == has_b:
            return 500, {"userMessage": "An unexpected error occurred."}
        segs = tail.split("/")
        double = bool(segs) and segs[0] == ""
        if ("ds_unmergedouble" in self.defects) != double:
            return 400, {"userMessage": "The value 'tablePath' is not valid."}
        if double:
            segs = segs[1:]
        if len(segs) < 3 or segs[-3:-1] != ["root", "Client"]:
            return 400, {"userMessage": "The value 'tablePath' is not valid."}
        golden = segs[-1]
        bare = space[1:] if has_b else space
        code = bare[:-len("_Master")] if bare.endswith("_Master") else ""
        meta = self.mds.get(code, {}).get("RecordMetadata")
        row = [m for m in meta or [] if str(m["functionalId"]) == golden]
        members = [m for m in meta or [] if m.get("goldenId") == golden and m["state"] == 2]
        if not row or not row[0].get("autoCreated") or row[0]["state"] != 1 or not members:
            return 400, {"userMessage": "Select a golden record that was created by a merge."}
        if "ds_unmergenoop" in self.defects:
            return 200, {}
        row[0]["state"] = 4
        back = 3 if "mm_unmergepending" in self.defects else 1
        for m in members:
            m.update({"state": back, "goldenId": None})
        if "ds_unmergesilent" not in self.defects:
            if "ds_unmergenoretire" not in self.defects:
                self.send_to(code, "Client", golden, self.retire_doc(golden, None, "UNMERGE", 1), "RETIRE")
            if back == 1:
                clients = dict((str(r["id"]), r) for r in self.master_data[code]["Client"])
                for m in members:
                    self.send_to(code, "Client", m["functionalId"], self.message_doc(clients[str(m["functionalId"])]), "CREATE")
        return 200, {}

    def delete_distribution(self, code):
        """The project deletion: the flows (Connectors mappings) of the targets, then the target and event rows (always done by the real Factory)."""
        for t in [t for t in self.table("DistributionTarget").values() if t["project"] == code]:
            if "ds_flowsleft" not in self.defects:
                for mid in list(self.parsed(t["connectorsMappingIds"]).values()):
                    self.dist_mappings.pop(mid, None)
            if "ds_targetsleft" not in self.defects:
                del self.table("DistributionTarget")[t["id"]]
        if "ds_targetsleft" not in self.defects:
            self.dist_events = [e for e in self.dist_events if e["target"] in self.table("DistributionTarget")]

    # ------------------------------------------------------------------------------------------------------------------ HTTP
    def handle(self, method, path, query, body, authorized):
        if not authorized:
            return 401, {"error": "no credentials"}
        with self.lock:
            return self.route(method, path, query, body)

    def route(self, method, path, query, body):
        m = DS_ROOT.match(path)
        if m:
            return self.data_services(method, m, query, body)
        if path == "/ebx-assistant/mcp/v1/match-merge" and method == "POST" and self.master:
            return 200, self.mcp_call(body)
        if path == "/ebx-addon-mame/rest/v1/manageGroup/acceptSuspects" and method == "POST" and self.master:
            return self.accept_suspects(body)
        um = UNMERGE_ROUTE.match(path)
        if um and method == "DELETE" and self.master:
            return self.unmerge(um.group(1), um.group(2), um.group(3))
        if not path.startswith(BASE):
            return 404, None
        rest = path[len(BASE):]
        if rest == "/status":
            return 200, {"module": "ebx-mdm-factory", "version": "stand-in", "commit": "000", "datasetState": "READY", "dataspace": "MdmFactory", "dataset": "MdmFactory"}
        if rest == "/connection/test":
            return 200, {"ready": True}
        if rest == "/sources/onboarding/connections" and method == "GET" and "noconnroute" not in self.defects:
            return 200, {"connections": [dict((k, v) for k, v in c.items() if k != "project") for c in self.connections.values()]}
        if rest == "/matching/orphans" and method == "GET" and "noorphanroute" not in self.defects:
            # lot M18: the add-on's rows of one publication (?publication=<code>_Master), as GET /matching/orphans answers them
            wanted = query.get("publication", [""])[0]
            found = []
            for code in sorted(self.addon_rows):
                if wanted and wanted != code + "_Master":
                    continue
                found.append({"publication": code + "_Master", "model": "GONE" if code not in self.table("Project") else "EXISTS", "eligible": True, "action": "UNLINK_AND_DELETE_BUSINESS_OBJECTS",
                              "businessObjects": [{"id": "9", "mainTable": "/root/Client"}],
                              "tableConfigurations": [{"id": "122", "table": "/root/Client", "businessObject": "9"}, {"id": "123", "table": "/root/Adresse", "businessObject": "9"}]})
            return 200, {"publications": found, "count": len(found), "eligibleCount": len(found), "confirmWith": str(len(found))}
        pm = re.match(r"^/projects/([^/]+)/(.+)$", rest)
        if not pm:
            return 404, None
        code, tail = pm.group(1), pm.group(2)
        known = code in self.table("Project")
        routes = {("POST", "sources/files"), ("POST", "landing/run"), ("POST", "staging/propose"), ("POST", "staging/decide"), ("GET", "staging/review"),
                  ("POST", "staging/decisions"), ("POST", "capabilities/apply"), ("POST", "staging/generate"), ("POST", "staging/load"), ("GET", "jobs"),
                  ("GET", "deletion/plan"), ("POST", "delete")}
        if self.hard:
            routes |= {("GET", "staging/preflight"), ("POST", "staging/preflight"), ("GET", "rejects/causes")}
        if self.master:
            routes |= {("POST", "master/build"), ("POST", "matching/propose"), ("POST", "matching/simulate-plan"), ("POST", "matching/configure"), ("POST", "matching/run"),
                       ("GET", "matching/activity"), ("GET", "rejects/causes")}
            if "nobuildroute" in self.defects:
                routes.discard(("POST", "master/build"))
        is_route = (method, tail) in routes or (method == "PUT" and re.match(r"^capabilities/[^/]+/binding$", tail))
        if self.master and self.is_distribution_route(method, tail):
            if not known:
                return 404, {"error": "Project %s not found" % code}
            return self.distribution_route(method, code, tail, query, body)
        if not is_route:
            return 404, None
        if not known:
            return 404, {"error": "Project %s not found" % code}
        if tail == "sources/files":
            return 200, self.import_files(code, json.loads(body))
        if tail == "landing/run":
            return 202, {"started": True, "jobId": self.landing_run(code)}
        if tail == "staging/propose":
            (self.propose_b2b if self.b2b else self.propose)(code)
            return 202, {"started": True, "jobId": max(self.jobs, key=int)}
        if tail == "staging/decide":
            return 202, {"started": True, "jobId": self.bulk(code, query.get("accept", ["true"])[0] == "true", int(query.get("threshold", ["60"])[0]))}
        if tail == "staging/review":
            status = query.get("status", ["PROPOSED"])[0]
            lines = self.lines(code)
            counts = {}
            for l in lines:
                counts[l["status"]] = counts.get(l["status"], 0) + 1
            return 200, {"lines": [l for l in lines if status == "ALL" or l["status"] == status], "counts": counts}
        if tail == "staging/decisions":
            res = []
            valid = {(l["nature"], l["id"]) for l in self.lines(code)}
            for d in json.loads(body)["decisions"]:
                row = self.row_for(d["nature"], d["id"])
                if (d["nature"], d["id"]) not in valid or row is None:
                    res.append({"nature": d["nature"], "id": d["id"], "ok": False, "reason": "not a proposal line of project"})
                elif d["status"] == "ACCEPTED" and self.accept_refusal(d["nature"], row):
                    res.append({"nature": d["nature"], "id": d["id"], "ok": False, "reason": self.accept_refusal(d["nature"], row)})
                else:
                    row["status"] = d["status"]
                    res.append({"nature": d["nature"], "id": d["id"], "ok": True, "newStatus": d["status"]})
            return 200, {"results": res}
        if method == "PUT":
            sid = tail.split("/")[1]
            row = self.table("CapabilitySuggestion").get(sid)
            if row is None:
                return 404, {"error": "Project %s not found" % code}
            data = json.loads(body)
            binding = json.loads(row["binding"])
            for k, v in data.items():
                binding[k] = {"literal": v}
            row["binding"] = json.dumps(binding)
            return 200, {"changed": True}
        if tail == "capabilities/apply":
            for i in query.get("ids", [""])[0].split(","):
                if i and i in self.table("CapabilitySuggestion"):
                    self.table("CapabilitySuggestion")[i]["status"] = "APPLIED"
            return 200, {"applied": 1, "failed": 0}
        if tail == "staging/generate":
            return 200, self.generate(code)
        if tail == "staging/preflight":
            if method == "GET":
                return 200, self.last_preflight or {"exists": False}
            apply = query.get("apply", [None])[0]
            fix = self.apply_fix(apply) if apply else None
            report = self.compute_preflight(code)
            self.last_preflight = report
            out = dict(report)
            if fix is not None:
                out["fix"] = fix
            return 200, out
        if tail == "rejects/causes":
            return 200, self.causes(code, query.get("stage", ["STAGING"])[0])
        if tail == "master/build":
            return 202, {"started": True, "jobId": self.master_build(code)}
        if tail == "matching/propose":
            return 202, {"started": True, "jobId": self.propose_matching(code)}
        if tail == "matching/simulate-plan":
            return self.simulate_plan(code, query.get("plan", [""])[0])
        if tail == "matching/configure":
            return 202, {"started": True, "jobId": self.configure_matching(code)}
        if tail == "matching/run":
            return 202, {"started": True, "jobId": self.run_matching(code)}
        if tail == "matching/activity":
            return 200, {"kind": "RUN", "status": "DONE", "entities": [{"name": "Client", "status": "DONE"}]}
        if tail == "staging/load":
            if self.hard:
                self.load_hard(code, query.get("buildAnyway", ["false"])[0] == "true")
            elif self.b2b:
                self.load_b2b(code)
            else:
                self.load(code)
            return 202, {"started": True, "jobId": max(self.jobs, key=int)}
        if tail == "jobs":
            active, recent = [], []
            for j in self.table("FactoryJob").values():
                if j["project"] != code:
                    continue
                polls = self.jobs[j["id"]]
                self.jobs[j["id"]] = polls + 1
                if polls < 1:
                    active.append({"jobId": j["id"], "status": "RUNNING", "kind": j["kind"], "stepMessage": "working"})
                else:
                    recent.append({"jobId": j["id"], "status": j["status"], "kind": j["kind"], "message": j["message"]})
            return 200, {"active": active, "recent": recent, "busy": bool(active)}
        if tail == "deletion/plan":
            items = [{"kind": "DATASPACE", "name": code + "_Landing", "option": "dataspaces", "eligible": True}]
            if self.master and code in self.addon_rows:      # lot M18: the business object first, its member, then the master model
                items = [{"kind": "MATCHING_BUSINESS_OBJECT", "name": "/root/Client", "option": "matching", "eligible": True},
                         {"kind": "MATCHING_CONFIG", "name": "/root/Client", "option": "matching", "eligible": True},
                         {"kind": "MATCHING_CONFIG", "name": "/root/Adresse", "option": "matching", "eligible": True}] + items \
                    + [{"kind": "MASTER_MODEL", "name": code + "_Master", "option": "models", "eligible": True}]
            if self.master:     # distribution (lot S1): the Connectors flows of the project's targets are in the plan, named by their mapping id
                items += [{"kind": "DISTRIBUTION_FLOW", "name": mid, "option": "flows", "eligible": True} for mid, f in self.dist_mappings.items() if f["project"] == code]
            if "delconn" in self.defects:      # lot C: the plan also lists the sample-file connections of the project
                items += [{"kind": "CONNECTION", "name": c["id"], "option": "flows", "eligible": True} for c in self.connections.values() if c["project"] == code]
            return 200, {"project": code, "items": items}
        if tail == "delete":
            jid = self.job(code, "PROJECT_DELETE", "Deleted.")
            self.delete_distribution(code)
            if "mm_boleft" not in self.defects:      # lot M18: the deletion removes the business object and the table configurations from the add-on's dataset
                self.addon_rows.discard(code)
            if self.hard or "delconn" in self.defects:
                self.table("Project").pop(code, None)
            if "delconn" in self.defects:
                for k in [k for k, c in self.connections.items() if c["project"] == code]:
                    del self.connections[k]
            return 202, {"jobId": jid}
        return 404, None

    def data_services(self, method, m, query, body):
        space, dataset, table, pk = m.group(1), m.group(2), m.group(3), m.group(4)
        if space == "MdmFactory":
            rows = self.table(table)
            if table == "FactorySettings":
                return (200, wrap(self.settings[pk])) if method == "GET" and pk in self.settings else (404, {"error": "no settings"})
        elif space == "Connectors":
            if table not in ("Connector", "ConnectorProperty"):
                return 404, {"error": "no such table"}
            rows = self.table(table)
        elif space.endswith("_Master"):
            code = space[:-len("_Master")]
            if dataset == "Master":
                source = self.master_data.get(code, {})
            elif dataset == "Master_Client_MDS" and self.metadata_exists(code):
                source = self.mds.get(code, {"RecordMetadata": [], "Suspected": []})
            else:
                return 404, {"error": "no such dataset"}
            if table not in source:
                return 404, {"error": "no such table"}
            rows = dict((str(i), r) for i, r in enumerate(source[table]))
            if dataset == "Master" and source[table] and all("id" in r for r in source[table]):
                rows = dict((str(r["id"]), r) for r in source[table])       # the master tables are addressed by their primary key, id
        else:
            rows = dict((str(i), r) for i, r in enumerate(self.staging.get(space[:-len("_Staging")], {}).get(table, [])))
            if not rows and table not in self.staging.get(space[:-len("_Staging")], {}):
                return 404, {"error": "no such table"}
        if method == "GET" and pk is None:
            flt = query.get("filter", [None])[0]
            selected = [r for r in rows.values() if not flt or evaluate(flt, r)]
            size = int(query.get("pageSize", ["200"])[0])
            page = int(query.get("page", ["0"])[0])
            chunk = selected[page * size:(page + 1) * size]
            doc = {"rows": [wrap(self.present(space, dataset, table, r)) for r in chunk]}
            if (page + 1) * size < len(selected):
                nxt = "/ebx-dataservices/rest/data/v1/B%s/%s/root/%s?pageSize=%d&page=%d" % (space, dataset, table, size, page + 1)
                if flt:
                    nxt += "&filter=" + urllib.parse.quote(flt, safe="")
                doc["pagination"] = {"nextPage": {"href": nxt}}
            return 200, doc
        if method == "GET":
            row = rows.get(urllib.parse.unquote(pk))
            return (200, wrap(self.present(space, dataset, table, row))) if row is not None else (404, {"error": "not found"})
        if method == "POST":
            for r in json.loads(body)["rows"]:
                fields = dict((k, v["content"]) for k, v in r["content"].items())
                if space == "Connectors" and table == "ConnectorProperty":
                    fields["id"] = "%s|%s" % (fields.get("connector"), fields.get("key"))
                self.add_project(fields) if table == "Project" else self.add(table, fields)
            return 201, {}
        if method == "DELETE":
            key = urllib.parse.unquote(pk or "")
            if space == "Connectors" and table == "Connector" and any(f["connector"] == key for f in self.dist_mappings.values()):
                return 409, {"error": "Forbidden: mappings still use the connector %s" % key}
            return (200, {"deletedCount": 1}) if rows.pop(key, None) is not None else (404, {"error": "not found"})
        if method == "PUT":
            row = rows.get(urllib.parse.unquote(pk))
            if row is None:
                return 404, {"error": "not found"}
            if table == "MatchPlan" and row.get("status") not in ("PROPOSED", "ACCEPTED"):
                return 403, {"error": "Forbidden to [UPDATE] the record: the plan is %s" % row.get("status")}
            for k, v in json.loads(body)["content"].items():
                row[k] = v["content"]
            if space.endswith("_Master") and dataset == "Master" and "ds_rtmissed" not in self.defects:
                self.trigger_fired(space[:-len("_Master")], table, row, "MODIFY")
            return 204, None
        return 405, None

    def present(self, space, dataset, table, row):
        """What Data Services answers for a row: the factory's ids as numbers, and the person master rows with the Match and Merge state field the add-on adds to the business table."""
        if space == "MdmFactory":
            return numeric_id(row)
        if space.endswith("_Master") and dataset == "Master" and table == "Client" and space[:-len("_Master")] in self.mds:
            return dict(row, Linked_Field_State=self.meta_state(space[:-len("_Master")], row["id"]))
        if space.endswith("_Master") and dataset == "Master" and table == "Achat" and space[:-len("_Master")] in self.mds:
            # a member table carries the add-on's state field itself (lot S4)
            return dict(row, Linked_Field_State=self.member_state(space[:-len("_Master")], table, row["id"]))
        return row

    def add_project(self, fields):
        fields = dict(fields)
        # 1.4.2: Project/style is read only at creation, so a client sends none and EBX applies the model's default (tools/e2e/b2c_scenario.py no longer posts it)
        fields.setdefault("style", "CONSOLIDATION")
        self.table("Project")[fields["code"]] = fields
        fields["id"] = fields["code"]


class Handler(BaseHTTPRequestHandler):
    factory = None

    def log_message(self, *a):
        pass

    def do_any(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8") if n else ""
        parts = urllib.parse.urlsplit(self.path)
        status, doc = self.factory.handle(self.command, urllib.parse.unquote(parts.path), urllib.parse.parse_qs(parts.query), body,
                                             self.headers.get("Authorization", "").startswith("Basic "))
        data = b"" if doc is None else json.dumps(doc).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_DELETE = do_any


def serve(defects=(), hard=False, master=False, b2b=False):
    """(server, factory): a stand-in on a free 127.0.0.1 port."""
    factory = FakeFactory(defects, hard, master, b2b)

    class H(Handler):
        pass
    H.factory = factory
    server = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, factory
