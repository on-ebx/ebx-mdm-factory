"""Steps 10 and 11 of the B2C master scenario (lot S1, 2026-10-18): the DISTRIBUTION of the golden records (wizard step 7) and the SUMMARY (wizard step 8).

Mixed into tools/e2e/b2c_master.py (class MasterScenario). Kept in a file of its own because it is long and because it talks to a second system, the Kafka broker, which the rest of the
scenario never touches.

    step 10  (only with --distribute)
        a  a Kafka connection of the run is created in the Connectors dataset (Data Services, the write the earlier sessions used), named after the project
        b  POST distribution/targets: a REALTIME target on that connection
        c  POST .../configure (one outbound mapping per master table, one topic per table)
        d  POST .../install-triggers (the real-time triggers and the outbox on the master model)
        e  POST .../first-full-send (a job; the target becomes ARMED)
        f  the topics are read with kafka-console-consumer.sh and measured against the ground truth and the metadataset: one message per GOLDEN record and none for a MERGED member,
           key = record id, the fields of sampled goldens equal the master row, one topic per table
        g  real time: one golden record is modified through Data Services, exactly one new message with the new value is expected
        h  the merge gap: two suspects are merged through the add-on's REST; the new golden record must arrive, and whether the merged members get a retire notice is MEASURED and printed
        h2 (lot S4) the retire path: the true pairs the run did not merge are listed (INFO); then a golden record WITH merged members is UNMERGED through the add-on's REST
           (DELETE /ebx-addon-mame/rest/v1/mergingProcesses/{dataspace}/{dataset}/root/<Table>/<golden id>) and the topic is read for up to 90 s: a RETIRE notice (`_change`) of the deleted golden
           and an UPSERT for each member that comes back; counts are METRIC/INFO, a FAIL only when nothing at all arrives
        i  POST .../stop
    step 11  the summary the wizard shows at its last step. It is rendered on the server (WizardPage), no REST route returns it, so the scenario reads - with the summary's own definitions -
             the facts it is built from and checks them against what it measured.

The broker is a machine-level resource that a person starts: when it does not answer the whole of step 10 is SKIPPED with one clear line (never a failure, never a long wait).
Topics are kept on the broker while the project is kept; the cleanup of the project (--cleanup, --cleanup-project) deletes the project's own topics (lot F4, 2026-10-19: only names that start
with <project code lower case>., through kafka-topics.sh --delete), unless --keep-topics is given; what could not be deleted is said in LEFT BEHIND.
"""
import re
import time
import urllib.parse

import b2c_scenario as S
import client
import e2e_lib as L
import master_lib as M

ADDON_ACCEPT = "/ebx-addon-mame/rest/v1/manageGroup/acceptSuspects"
ADDON_UNMERGE = "/ebx-addon-mame/rest/v1/mergingProcesses/{dataspace}/{dataset}/root/{table}/{golden id}"      # DELETE, the add-on's `unMerge`
CONNECTORS_SPACE, CONNECTORS_DATASET = "Connectors", "Connectors"

# the routes step 10 needs, probed at step 0 only when --distribute is given (an older build without them ends with exit 3 and names them)
DIST_ROUTES = [("GET", "/projects/{x}/distribution/connections", None, "list the connections that can receive golden records"),
               ("GET", "/projects/{x}/distribution/targets", None, "list the distribution targets"),
               ("POST", "/projects/{x}/distribution/targets", "{}", "create a distribution target"),
               ("POST", "/projects/{x}/distribution/targets/0/configure", None, "configure the outbound mappings (one topic per table)"),
               ("POST", "/projects/{x}/distribution/targets/0/install-triggers", None, "install the real-time triggers"),
               ("POST", "/projects/{x}/distribution/targets/0/first-full-send", None, "first full send (arms the target)"),
               ("POST", "/projects/{x}/distribution/targets/0/stop", None, "stop the target's sends"),
               ("GET", "/projects/{x}/distribution/events", None, "list the distribution events")]
DIST_OPTIONAL_ROUTES = [("POST", "/projects/{x}/distribution/targets/0/disarm", None, "disarm the target at the end of the run")]


class DistributionSteps(object):
    """Mixin of MasterScenario: steps 10 and 11, their cleanup and their left-behind lines."""

    DIST_CHECKS = "distribution (connection, target, mappings, triggers, first send, topics, real time, merge notices)"

    def init_distribution(self):
        self.kafka = L.kafka_settings(self.env)
        self.dist_connector = ""            # the id of the Connectors connection of this run (deterministic: Kafka<project code>)
        self.dist_connector_created = False
        self.dist_target = ""               # the id of the DistributionTarget
        self.dist_mappings = {}             # table path -> Connectors mapping id (read back from the target)
        self.dist_topics = {}               # table path -> topic
        self.dist_ran = False               # step 10 created something
        self.dist_rt_changed = False        # a master record was modified by the real-time test
        self.dist_merged = False            # two suspects were merged by the retire-gap test
        self.dist_unmerged = False          # a golden record was unmerged by the retire test (lot S4)
        self.dist_disarmed = False
        self.dist_first_message = ""
        self.dist_mapping_ids = set()       # the mapping ids the deletion plan may name (read from the target rows before a deletion)
        self.dist_deleted_connector = False
        self.dist_summary_state = {}
        self.dist_topics_left = None        # None = the cleanup did not deal with the topics; else {topic: why it is still on the broker} (lot F4)
        self.details["distribution"] = {}

    # ------------------------------------------------------------------------------------------------------------------ the broker and its topics
    def broker_probe(self):
        probe = self.args.get("broker_probe")
        return probe() if probe is not None else L.kafka_probe(self.kafka)

    def read_topic(self, topic):
        reader = self.args.get("topic_reader")
        if reader is None:
            reader = L.kafka_cli_reader(self.kafka)
        return reader(topic, self.kafka["idle_ms"])

    def topic_lister(self):
        lister = self.args.get("topic_lister")
        return lister if lister is not None else L.kafka_topic_lister(self.kafka)

    def topic_deleter(self):
        deleter = self.args.get("topic_deleter")
        return deleter if deleter is not None else L.kafka_topic_deleter(self.kafka)

    def settle(self):
        """How long to wait after the expected message has arrived before looking for a second one (a duplicate comes within a dispatcher pass or two): 3 poll intervals, 15 s by default."""
        return float(self.env.get("E2E_DIST_SETTLE", str(3 * self.interval())))

    def step0_distribution(self):
        """Early warning at the start of a --distribute run: a down broker is said before the 40 minutes of the matching, not after."""
        if not self.args.get("distribute"):
            return
        ok, why = self.broker_probe()
        if ok:
            self.rep.info("0", "Kafka broker for the distribution step", why)
        else:
            self.rep.skipped("0", "Kafka broker for the distribution step", why + " - step 10 will be probed again when it starts, and skipped if the broker is still down")

    # ------------------------------------------------------------------------------------------------------------------ small readers
    def targets(self):
        r = self.get("/projects/%s/distribution/targets" % self.code)
        data = r.json()
        rows = [t for t in (data.get("targets") or []) if isinstance(t, dict)] if r.status == 200 and isinstance(data, dict) else []
        return r, rows

    def my_target(self):
        r, rows = self.targets()
        for t in rows:
            if L.text(t.get("id")) == self.dist_target:
                return t
        return None

    def events(self, limit=500):
        r = self.get("/projects/%s/distribution/events?targetId=%s&limit=%d" % (self.code, self.dist_target, limit))
        data = r.json()
        return [e for e in (data.get("events") or []) if isinstance(e, dict)] if r.status == 200 and isinstance(data, dict) else []

    def master_table_rows(self, table):
        r, rows = self.read_table(self.mst, table)
        return r, rows

    # ------------------------------------------------------------------------------------------------------------------ step 10
    def step10(self):
        if not self.args.get("distribute"):
            self.rep.skipped("10", "distribution (wizard step 7)", "not requested: add --distribute (it writes a Kafka connection, a distribution target, triggers on the master model and messages on the broker)")
            return
        ok, why = self.broker_probe()
        if not ok:
            self.rep.skipped("10", self.DIST_CHECKS, "SKIPPED, not failed: " + why + ". The broker is started by a person; run again with --distribute once it answers")
            self.details["distribution"] = {"skipped": why}
            return
        self.rep.info("10", "Kafka broker", why)
        try:
            self.dist_connection()
            self.dist_target_create()
            self.dist_configure()
            self.dist_triggers()
            self.dist_first_send()
            ctx = self.dist_topics_check()
            self.dist_realtime(ctx)
            self.dist_live_only_guard(ctx)
            self.dist_merge_notices(ctx)
            self.dist_unmerge_notices(ctx)
        except S.Stop:
            pass       # already reported as a FAIL; the target is stopped below and step 11 still runs
        finally:
            if self.dist_target:
                self.dist_stop()

    # ---- a. the connection
    def dist_connection(self):
        cid = "Kafka" + self.code
        self.dist_connector = cid
        bootstrap = self.kafka["bootstrap"]
        conn = client.Ds(self.admin, CONNECTORS_SPACE, CONNECTORS_DATASET)
        row = {"id": cid, "label": "Kafka - e2e %s" % self.code, "category": "MESSAGING", "type": "kafka", "baseUrl": bootstrap, "authType": "NONE", "trust": 50, "active": True,
               "description": "Scratch target of the end-to-end scenario tools/e2e/b2c_master.sh --distribute (project %s): golden records to topics %s.<table>. Safe to delete." % (self.code, self.code.lower())}
        r = conn.create("Connector", row)
        self.dist_connector_created = r.status in (200, 201)
        failed = None if self.dist_connector_created else "HTTP %d %s" % (r.status, S.clip(r.error_text()))
        props = [("envelope", "false"), ("group.id", "ebx-mdm-" + self.code.lower()), ("key.field", "id"), ("topic", self.code.lower())]
        if not failed:
            for key, value in props:
                rp = conn.create("ConnectorProperty", {"connector": cid, "key": key, "value": value})
                if rp.status not in (200, 201):
                    failed = "property %s: HTTP %d %s" % (key, rp.status, S.clip(rp.error_text()))
                    break
        self.rep.check("10", "Kafka connection created in Connectors (%s: kafka at %s, topic prefix %s)" % (cid, bootstrap, self.code.lower()), failed is None,
                       "Connectors/Connector %s with %d properties (envelope=false, key.field=id)" % (cid, len(props)), failed or "")
        if failed:
            raise S.Stop()
        self.dist_ran = True

        def listed():
            resp = self.get("/projects/%s/distribution/connections" % self.code)
            data = resp.json()
            for c in (data.get("connections") or []) if resp.status == 200 and isinstance(data, dict) else []:
                if L.text(c.get("connector")) == cid:
                    return c
            return None
        try:
            c = L.poll(listed, lambda c: c is not None and bool(c.get("writeOperations")), self.timeout("CONNECTION", 30), self.interval(), "the new connection to be listed with a write operation")
        except L.Timeout:
            c = listed()
        writes = [L.text(w) for w in (c or {}).get("writeOperations") or []]
        self.dist_operation = "publish" if "publish" in writes or not writes else writes[0]
        self.rep.check("10", "the new connection is a candidate for a target, with a write operation (publish)", c is not None and "publish" in writes,
                       "GET distribution/connections lists %s: write operations %s, topic %s, key field %s" % (cid, ",".join(writes), L.text((c or {}).get("topic")), L.text((c or {}).get("keyField"))),
                       "%s in GET distribution/connections: %s" % (cid, "not listed (an inactive or unreadable connection is left out)" if c is None else "write operations %s, describeError %s" % (writes, L.text(c.get("describeError")))))

    # ---- b. the target
    def dist_target_create(self):
        body = {"connector": self.dist_connector, "operation": self.dist_operation, "mode": "REALTIME", "cron": "", "scope": "", "approval": False, "watchedFields": "",
                "includeCrosswalk": False, "includePending": False, "retireMode": "NOTICE", "retireUndoneMerges": True}
        r = self.post("/projects/%s/distribution/targets" % self.code, body)
        data = r.json()
        ok = r.status == 200 and isinstance(data, dict) and data.get("status") == "DONE" and L.text(data.get("id")) != ""
        if ok:
            self.dist_target = L.text(data.get("id"))
        row = self.my_target() if ok else None
        good = ok and row is not None and L.text(row.get("mode")) == "REALTIME" and L.text(row.get("state")) == "DRAFT" and L.text(row.get("connector")) == self.dist_connector
        self.rep.check("10", "distribution target created on the new connection (REALTIME, state DRAFT)", good,
                       "target %s: %s / %s, mode REALTIME, state DRAFT" % (self.dist_target, self.dist_connector, self.dist_operation),
                       "HTTP %d %s; read back: %s" % (r.status, S.clip(r.error_text() if r.status != 200 else r.text), S.clip(str(row), 200)))
        if not good:
            raise S.Stop()

    # ---- c. configure
    def dist_configure(self):
        r = self.post("/projects/%s/distribution/targets/%s/configure" % (self.code, self.dist_target), slow=True)
        data = r.json()
        ok = r.status == 200 and isinstance(data, dict) and data.get("status") == "DONE"
        self.rep.check("10", "configure: the outbound mappings are written (job-less route, status DONE)", ok, S.clip(r.text, 200), "HTTP %d %s" % (r.status, S.clip(r.error_text())))
        if not ok:
            raise S.Stop()
        row = self.my_target() or {}
        self.dist_mappings = M.parse_pairs(row.get("connectorsMappingIds"))
        self.dist_topics = M.parse_pairs(row.get("tableDestinations"))
        wanted = ["/root/" + name for name in sorted((self.details.get("masterCounts") or {}))]
        lacking = [t for t in wanted if t not in self.dist_mappings]
        self.rep.check("10", "configure: one outbound mapping per master table (%s)" % ", ".join(sorted(self.dist_mappings)), bool(self.dist_mappings) and not lacking,
                       "%d mapping(s): %s" % (len(self.dist_mappings), ", ".join("%s=%s" % kv for kv in sorted(self.dist_mappings.items()))),
                       "master table(s) without a mapping: %s (target has %s)" % (", ".join(lacking), S.clip(L.text(row.get("connectorsMappingIds")), 200)))
        topics = [self.dist_topics.get(t, "") for t in sorted(self.dist_mappings)]
        self.rep.check("10", "one topic per table: %d tables, %d distinct topics" % (len(self.dist_mappings), len(set(topics))), bool(topics) and all(topics) and len(set(topics)) == len(topics),
                       "; ".join("%s -> %s" % (t, self.dist_topics.get(t)) for t in sorted(self.dist_mappings)),
                       "tableDestinations '%s': %s" % (L.text(row.get("tableDestinations")), "a table has no topic" if not all(topics) else "tables share a topic"))
        named = [t for t in sorted(self.dist_mappings) if self.dist_topics.get(t) != M.expected_topic(self.code, t)]
        self.rep.check("10", "the topics are named after the project (%s.<table>)" % self.code.lower(), not named, "; ".join(self.dist_topics.get(t, "") for t in sorted(self.dist_mappings)),
                       "; ".join("%s is %s, expected %s" % (t, self.dist_topics.get(t), M.expected_topic(self.code, t)) for t in named))
        self.details["distribution"].update({"connector": self.dist_connector, "target": self.dist_target, "mappings": self.dist_mappings, "topics": self.dist_topics})
        self.dist_mapping_ids = set(self.dist_mappings.values())

    # ---- d. triggers
    def dist_triggers(self):
        model = self.code + "_Master"
        r = self.post("/projects/%s/distribution/targets/%s/install-triggers?modelName=%s" % (self.code, self.dist_target, urllib.parse.quote(model)), slow=True)
        data = r.json()
        ok = r.status == 200 and isinstance(data, dict) and data.get("status") == "DONE"
        self.rep.check("10", "install-triggers: the real-time triggers are installed on the master model %s" % model, ok, "status DONE", "HTTP %d %s" % (r.status, S.clip(r.error_text())))
        if not ok:
            raise S.Stop()
        if data.get("matchingNotActiveHint"):
            self.rep.info("10", "install-triggers: a master table has no Match and Merge state field", "the RETIRE branch was not generated for it (matchingNotActiveHint); matching ran before, so this is a member table or a defect")

    # ---- e. first full send
    def dist_first_send(self):
        job = self.start_job("10", "first full send of the golden records", "/projects/%s/distribution/targets/%s/first-full-send" % (self.code, self.dist_target),
                             self.timeout("FIRSTSEND", 1800), "the first full send")
        self.dist_first_message = L.text(job.get("message"))
        row = self.my_target() or {}
        armed = L.text(row.get("state")) == "ARMED" and L.text(row.get("firstFullSendRunId")) != ""
        self.rep.check("10", "the target is ARMED after the first full send (firstFullSendRunId recorded)", armed,
                       "state ARMED, run %s, last status %s, last message: %s" % (L.text(row.get("firstFullSendRunId")), L.text(row.get("lastStatus")), S.clip(L.text(row.get("lastMessage")), 120)),
                       "state %s, firstFullSendRunId '%s', last status %s: %s" % (L.text(row.get("state")), L.text(row.get("firstFullSendRunId")), L.text(row.get("lastStatus")), S.clip(L.text(row.get("lastMessage")), 200)))
        if not armed:
            raise S.Stop()

    # ---- f. the topics
    def dist_topics_check(self):
        person_path = "/root/" + self.person_table
        ctx = {"personPath": person_path, "messages": {}, "audit": {}}
        # fresh reads: the master tables and the metadataset as they are NOW (the matching run may have added the add-on's goldens after the rows were first read)
        r, person_rows = self.master_table_rows(self.person_table)
        r_meta, meta = self.read_table(self.mds, "RecordMetadata")
        r_susp, suspects = self.read_table(self.mds, "Suspected")
        pairs = [(L.text(s.get("sourceId")), L.text(s.get("targetId"))) for s in suspects] if r_susp.status == 200 else []
        sets = M.publication_sets([L.text(x.get("id")) for x in person_rows], meta)
        ctx.update({"rows": person_rows, "meta": meta, "suspects": pairs, "sets": sets})
        self.details["distribution"]["golden"] = {"goldenRecords": len(sets["golden"]), "merged": len(sets["merged"]), "deleted": len(sets["deleted"]), "pending": len(sets["pending"]),
                                                  "autoCreated": len(sets["autoCreated"]), "masterRows": len(person_rows)}
        total_messages = 0
        for path in sorted(self.dist_mappings):
            topic = self.dist_topics.get(path, "")
            msgs, err = self.read_topic(topic)
            ctx["messages"][path] = msgs
            if err:
                self.rep.failed("10", "%s: the topic %s can be read" % (path, topic), err)
                continue
            total_messages += len(msgs)
            if path == person_path:
                self.person_topic_checks(ctx, path, topic, msgs)
            else:
                self.other_topic_checks(ctx, path, topic, msgs)
        self.job_count_check(total_messages)
        return ctx

    def person_topic_checks(self, ctx, path, topic, msgs):
        sets, rows = ctx["sets"], ctx["rows"]
        a = M.audit_publication(msgs, sets)
        ctx["audit"][path] = a
        golden = len(sets["golden"])
        self.rep.info("10", "METRIC messages on %s = %d (%d golden records in %s, %d merged members, %d deleted, %d pending)" % (
            topic, a["messages"], golden, self.person_table, len(sets["merged"]), len(sets["deleted"]), len(sets["pending"])),
            "%d record message(s) (_change: %s), %d retire notice(s), %d delete notice(s), %d not JSON" % (
                a["data"], ", ".join("%s %d" % kv for kv in sorted(a["changes"].items())) or "none", len(a["notices"]), len(a["deletes"]), a["notJson"]))
        self.rep.check("10", "%s: one message per golden record (%d)" % (topic, golden), not a["missing"] and not a["duplicates"] and a["notJson"] == 0 and a["data"] > 0,
                       "%d golden records, %d data messages, none missing, none twice" % (golden, a["data"]),
                       "%d golden record(s) with no message: %s | %d published more than once: %s | %d message(s) that are not JSON" % (
                           len(a["missing"]), M.listing(a["missing"]), len(a["duplicates"]), M.listing(a["duplicates"]), a["notJson"]))
        merged, deleted = a["mergedPublished"], a["deletedPublished"]
        self.rep.check("10", "%s: no message for a merged member or an unmerged golden (%d merged, %d deleted in the metadataset)" % (topic, len(sets["merged"]), len(sets["deleted"])),
                       not merged and not deleted, "none of the %d merged members and %d deleted goldens was sent" % (len(sets["merged"]), len(sets["deleted"])),
                       "%d merged member(s) published: %s | %d deleted golden(s) published: %s" % (len(merged), M.listing(merged), len(deleted), M.listing(deleted)))
        self.rep.check("10", "%s: every key is the id of a master record of %s" % (topic, self.person_table), not a["unknown"],
                       "%d distinct key(s), all master ids" % len(a["published"]), "%d key(s) that are not a master record: %s" % (len(a["unknown"]), M.listing(a["unknown"])))
        self.rep.check("10", "%s: the key of every message is the id the message carries" % topic, not a["keyMismatch"] and a["data"] > 0, "%d message(s) checked" % a["data"],
                       "%d message(s) whose key differs from their id: %s" % (len(a["keyMismatch"]), M.listing("%s!=%s" % kv for kv in a["keyMismatch"])))
        self.sampled_survivors(ctx, topic, a)
        self.foreign_check(ctx, path, topic, a, rows)

    def sampled_survivors(self, ctx, topic, a):
        """Three goldens (an add-on golden first, when there is one): the fields of the message equal the master row's."""
        sets, rows = ctx["sets"], ctx["rows"]
        by_id = dict((L.text(r.get("id")), r) for r in rows)
        docs = {}
        for key, doc in a["docs"]:
            docs.setdefault(key, doc)
        order = sorted(sets["golden"], key=M.num_key)
        sample = [i for i in order if i in sets["autoCreated"]][:1]
        sample += [i for i in order if i not in sample][:3 - len(sample)]
        sample = [i for i in sample if i in docs and i in by_id]
        bad = []
        for rid in sample:
            for field, got, want in M.compare_message_to_row(docs[rid], by_id[rid]):
                bad.append("%s.%s: message %r, master %r" % (rid, field, got, want))
        self.rep.check("10", "%s: the fields of %d sampled golden records equal the master row" % (topic, len(sample)), bool(sample) and not bad,
                       "records %s: every field of the message equals the master row" % ", ".join(sample), "no golden record with a message to sample" if not sample else "; ".join(bad[:8]))
        self.details["distribution"]["sampledGoldens"] = sample

    def foreign_check(self, ctx, path, topic, a, rows):
        columns = list(rows[0].keys()) if rows else []
        foreign = M.foreign_fields([doc for _k, doc in a["docs"]], columns)
        self.rep.check("10", "%s: only records of %s on the topic (one topic per table)" % (topic, M.table_name(path)), not foreign,
                       "every field of every message is a column of the table (technical fields starting with an underscore, such as _change, are expected)", "fields that are not columns of %s: %s" % (M.table_name(path), ", ".join("%s x%d" % kv for kv in sorted(foreign.items()))))

    def other_topic_checks(self, ctx, path, topic, msgs):
        name = M.table_name(path)
        r, rows = self.master_table_rows(name)
        stated = M.has_linked_state(rows)
        # a member table (Adresse) carries Match and Merge's Linked_Field_State itself: read the golden rule from it, as for the person table (lot S4)
        sets = M.publication_sets_from_rows(rows) if stated else M.publication_sets([L.text(x.get("id")) for x in rows], [])
        a = M.audit_publication(msgs, sets)
        ctx["audit"][path] = a
        ctx.setdefault("tableSets", {})[path] = sets
        self.rep.check("10", "%s: the table %s has its own messages (%d master rows)" % (topic, name, len(rows)), r.status == 200 and (not rows or a["data"] > 0),
                       "%d message(s) for %d master row(s)" % (a["data"], len(rows)), "HTTP %d, %d master row(s) and no message on the topic" % (r.status, len(rows)))
        figure, explained = M.member_table_figure(rows, a["data"])
        if stated:
            held = len(sets["merged"]) + len(sets["deleted"])
            self.rep.check("10", "%s: no message for a merged or deleted row of %s (%d merged, %d deleted by Linked_Field_State)" % (topic, name, len(sets["merged"]), len(sets["deleted"])),
                           not a["mergedPublished"] and not a["deletedPublished"], "none of the %d merged or deleted row(s) was sent" % held,
                           "%d merged row(s) published: %s | %d deleted row(s) published: %s" % (len(a["mergedPublished"]), M.listing(a["mergedPublished"]), len(a["deletedPublished"]), M.listing(a["deletedPublished"])))
            if held and explained:
                self.rep.info("10", "METRIC messages on %s = %d for %d master rows of %s: expected, the MERGED rows are not sent" % (topic, a["data"], len(rows), name),
                              "%s. Match and Merge's cascade merge gives a member table golden copies of its rows and leaves the originals MERGED; the target sends the GOLDEN rows only, so the messages equal the GOLDEN rows" % figure)
            elif explained:
                self.rep.info("10", "METRIC messages on %s = %d for %d master rows of %s" % (topic, a["data"], len(rows), name), "one message per row: %s" % figure)
            else:
                self.rep.info("10", "METRIC messages on %s = %d for %d master rows of %s: not the GOLDEN rows" % (topic, a["data"], len(rows), name),
                              "%s; %d GOLDEN row(s) with no message, %d key(s) sent twice, %d key(s) that are not a row" % (figure, len(a["missing"]), len(a["duplicates"]), len(a["unknown"])))
            self.details["distribution"].setdefault("memberTables", {})[name] = {"rows": len(rows), "golden": len(sets["golden"]), "merged": len(sets["merged"]), "deleted": len(sets["deleted"]),
                                                                                  "pending": len(sets["pending"]), "messages": a["data"]}
        else:
            same = a["data"] == len(rows) and not a["duplicates"]
            self.rep.info("10", "METRIC messages on %s = %d for %d master rows of %s" % (topic, a["data"], len(rows), name),
                          "one message per row" if same else "not judged for a table with no Linked_Field_State column: %d row(s) with no message, %d key(s) sent twice, %d key(s) that are not a row" % (
                              len(a["missing"]), len(a["duplicates"]), len(a["unknown"])))
        self.foreign_check(ctx, path, topic, a, rows)

    def job_count_check(self, total_messages):
        m = re.search(r"Sent (\d+) row", self.dist_first_message)
        if not m:
            self.rep.info("10", "the first full send's own count", "not comparable: the job says '%s'" % S.clip(self.dist_first_message, 160))
            return
        sent = int(m.group(1))
        u = re.search(r"(\d+) unchanged", self.dist_first_message)
        sent += int(u.group(1)) if u else 0
        self.rep.info("10", "the first full send says %d row(s), %d message(s) were read from the topics" % (sent, total_messages), "same" if sent == total_messages else "differ: see the checks above")

    # ---- g. real time
    def dist_realtime(self, ctx):
        name = "real time: a golden record modified through Data Services reaches the topic as exactly one new message"
        path = ctx["personPath"]
        topic = self.dist_topics.get(path, "")
        if not ctx["audit"].get(path):
            self.rep.skipped("10", name, "the person topic could not be read above")
            return
        record = M.pick_realtime_record(ctx["rows"], ctx["sets"], ctx["meta"], ctx["suspects"])
        if record is None:
            self.rep.skipped("10", name, "no golden record to modify")
            return
        rid = L.text(record.get("id"))
        done, refused = None, []
        t_put = time.time()
        for field, new in M.free_text_fields(record)[:3]:
            self.guard.allow_path(self.mst.record(self.person_table, rid))
            t_put = time.time()
            r = self.mst.update(self.person_table, rid, {field: new})
            if r.status in (200, 204):
                done = (field, new)
                break
            refused.append("%s: HTTP %d %s" % (field, r.status, S.clip(r.error_text(), 100)))
        for line in refused:
            self.rep.info("10", "real time: a write was refused", line)
        if done is None:
            self.rep.skipped("10", name, "no text field of record %s accepted a write (%s)" % (rid, "; ".join(refused) or "none qualified"))
            return
        field, new = done
        self.dist_rt_changed = True
        before = ctx["messages"][path]

        def fetch():
            msgs, err = self.read_topic(topic)
            fresh = M.new_messages(before, msgs) if not err else []
            return msgs, err, fresh, [m for m in fresh if L.text(m.get("key")) == rid]
        try:
            L.poll(fetch, lambda v: v[1] is not None or bool(v[3]), self.timeout("REALTIME", 60), self.interval(), "the real-time message of record %s" % rid)
        except L.Timeout:
            pass
        latency = time.time() - t_put
        time.sleep(self.settle())        # a duplicate would come within a dispatcher pass or two
        msgs, err, fresh, mine = fetch()
        ctx["messages"][path] = msgs if not err else before
        events = [e for e in self.events() if L.text(e.get("recordKey")) == rid]
        if err:
            self.rep.failed("10", name, "the topic could not be read: " + err)
            return
        docs = [M.message_doc(m.get("value")) or {} for m in mine]
        value_ok = len(docs) == 1 and M.values_equal(docs[0].get(field), new)
        self.rep.check("10", name, len(mine) == 1 and value_ok,
                       "record %s, %s set to '%s': %d new message with that value, about %d s after the write (%s)" % (rid, field, new, len(mine), int(latency),
                                                                                                                      "event %s" % ", ".join("%s %s" % (e.get("change"), e.get("status")) for e in events) if events else "no event listed"),
                       "record %s, %s set to '%s': %d new message(s) for it after %d s (%s), value(s) %s | events: %s" % (
                           rid, field, new, len(mine), int(latency), "waited " + str(self.timeout("REALTIME", 60)) + " s", [d.get(field) for d in docs],
                           ", ".join("%s %s %s" % (e.get("change"), e.get("status"), S.clip(L.text(e.get("message")), 60)) for e in events) or "none"))
        others = [m for m in fresh if L.text(m.get("key")) != rid]
        self.rep.info("10", "METRIC real-time latency = about %d s (write to first sighting on the topic, including the CLI read); other new messages on the topic = %d" % (int(latency), len(others)),
                      "a write on one record also moved: %s" % M.listing(L.text(m.get("key")) for m in others) if others else "nothing else was sent")
        self.details["distribution"]["realtime"] = {"record": rid, "field": field, "value": new, "newMessages": len(mine), "latencySeconds": int(latency), "events": [dict(e) for e in events[:5]]}

    # ---- g2. the live-only guard (lot S4)
    def dist_live_only_guard(self, ctx):
        """Regression guard for lot S2's live-only sends, run AFTER the real-time write: no topic may hold a RECORD message (UPSERT, not a retire or delete notice) whose key is a record Match
        and Merge holds as MERGED or DELETED now. The person table's states come from its metadataset, a member table's from its own Linked_Field_State. A violation is a FAIL."""
        name = "live-only sends: after the real-time write no topic holds a record message for a MERGED or DELETED record"
        path = ctx["personPath"]
        if not ctx["audit"].get(path):
            self.rep.skipped("10", name, "the person topic could not be read above")
            return
        r_meta, meta = self.read_table(self.mds, "RecordMetadata")
        r_rows, rows = self.master_table_rows(self.person_table)
        person_sets = M.publication_sets([L.text(x.get("id")) for x in rows], meta)
        problems, checked, unread = [], [], []
        for table_path in sorted(self.dist_mappings):
            topic = self.dist_topics.get(table_path, "")
            if table_path == path:
                msgs, sets = ctx["messages"].get(path) or [], person_sets
            else:
                tname = M.table_name(table_path)
                r_t, trows = self.master_table_rows(tname)
                if not M.has_linked_state(trows):
                    continue          # no state to judge the messages by
                sets = M.publication_sets_from_rows(trows)
                msgs, err = self.read_topic(topic)
                if err:
                    unread.append("%s: %s" % (topic, S.clip(err, 100)))
                    continue
                ctx["messages"][table_path] = msgs
            merged, deleted = M.live_only_violations(msgs, sets)
            checked.append("%s (%d merged, %d deleted)" % (topic, len(sets["merged"]), len(sets["deleted"])))
            if merged or deleted:
                problems.append("%s: %d merged record(s) sent as live: %s | %d deleted record(s) sent as live: %s" % (topic, len(merged), M.listing(merged), len(deleted), M.listing(deleted)))
        if unread and not checked:
            self.rep.skipped("10", name, "no topic could be read again: " + "; ".join(unread))
            return
        self.rep.check("10", name, not problems and bool(checked), "topics checked: %s" % "; ".join(checked), "; ".join(problems) or "no topic could be judged")
        for line in unread:
            self.rep.info("10", "live-only sends: a topic could not be read again", line)

    # ---- h. the merge gap
    def dist_merge_notices(self, ctx):
        name = "merge two suspects: the new golden record is sent"
        path = ctx["personPath"]
        topic = self.dist_topics.get(path, "")
        if not ctx["audit"].get(path):
            self.rep.skipped("10", name, "the person topic could not be read above")
            return
        r_meta, meta = self.read_table(self.mds, "RecordMetadata")
        r_susp, suspects = self.read_table(self.mds, "Suspected")
        pairs = [(L.text(s.get("sourceId")), L.text(s.get("targetId"))) for s in suspects] if r_susp.status == 200 else []
        r_rows, rows = self.master_table_rows(self.person_table)
        sets = M.publication_sets([L.text(x.get("id")) for x in rows], meta)
        touched = [L.text(e) for e in (self.details["distribution"].get("realtime") or {}).get("record", "").split()]
        pick = M.pick_suspect_pair(pairs, sets, meta, avoid=touched)
        if pick is None:
            self.rep.skipped("10", name, "no suspect pair between two source golden records is left after the run (%d pair(s) in Suspected): nothing to merge, the retire gap is not measured" % len(pairs))
            return
        source, target, group = pick
        body = {"dataspace": "B" + self.master_space, "dataset": self.master_dataset, "tablePath": "/root/" + self.person_table, "groupId": M.group_number(group), "recordPrimaryKeys": [source]}
        resp = self.admin.request("POST", ADDON_ACCEPT, body)
        if resp.status in (400, 404, 500):       # the dataspace may be wanted without its B prefix (the shape was not seen live): one more try, the first one changed nothing
            body = dict(body, dataspace=self.master_space)
            resp = self.admin.request("POST", ADDON_ACCEPT, body)
        if resp.status not in (200, 204):
            self.rep.info("10", "acceptSuspects refused (the request shape was never seen live)", "HTTP %d %s; body %s" % (resp.status, S.clip(resp.error_text(), 200), {k: v for k, v in body.items()}))
            self.rep.skipped("10", name, "the add-on refused POST manageGroup/acceptSuspects: the merge was not made, the retire gap is not measured")
            return
        self.dist_merged = True
        ids = [source, target]

        def fetch_meta():
            return self.read_table(self.mds, "RecordMetadata")[1]
        try:
            meta_now = L.poll(fetch_meta, lambda rows_: M.merge_outcome(rows_, ids)[1] is not None, self.timeout("MERGE", 120), self.interval(), "records %s and %s to be merged" % (source, target))
        except L.Timeout:
            meta_now = fetch_meta()
            states, _g = M.merge_outcome(meta_now, ids)
            self.rep.info("10", "acceptSuspects answered %d but records %s and %s were not merged" % (resp.status, source, target),
                          "states after %d s: %s (the add-on accepted the suspect into the group without merging it)" % (self.timeout("MERGE", 120), {k: M.STATE_NAMES.get(v) for k, v in states.items()}))
            self.rep.skipped("10", name, "no merge happened, so there is nothing to notify")
            return
        states, golden = M.merge_outcome(meta_now, ids)
        self.rep.info("10", "records %s and %s merged by the add-on into golden record %s" % (source, target, golden), "acceptSuspects HTTP %d, group %s" % (resp.status, group))
        before = ctx["messages"][path]

        def fetch():
            msgs, err = self.read_topic(topic)
            return msgs, err, (M.new_messages(before, msgs) if not err else [])

        def golden_in(v):
            return any(L.text(m.get("key")) == golden and not M.is_retire_notice(M.message_doc(m.get("value"))) for m in v[2])
        try:
            L.poll(fetch, lambda v: v[1] is not None or golden_in(v), self.timeout("REALTIME", 60), self.interval(), "the message of the new golden record %s" % golden)
        except L.Timeout:
            pass
        time.sleep(self.settle())         # the retire notices are queued by the dispatcher's merge-log pass, which comes after the golden's own event
        msgs, err, fresh = fetch()
        if err:
            self.rep.failed("10", name, "the topic could not be read: " + err)
            return
        golden_data = [m for m in fresh if L.text(m.get("key")) == golden and not M.is_retire_notice(M.message_doc(m.get("value")))]
        retire = [m for m in fresh if L.text(m.get("key")) in ids and M.is_retire_notice(M.message_doc(m.get("value")))]
        resent = [m for m in fresh if L.text(m.get("key")) in ids and not M.is_retire_notice(M.message_doc(m.get("value")))]
        retire_keys = sorted(set(L.text(m.get("key")) for m in retire), key=M.num_key)
        r_row, grow = self.mst.get_row(self.person_table, golden)
        diffs = M.compare_message_to_row(M.message_doc(golden_data[0].get("value")) or {}, grow or {}) if golden_data and grow else []
        self.rep.check("10", "%s (golden record %s of %s + %s)" % (name, golden, source, target), len(golden_data) == 1 and not diffs,
                       "1 message for golden record %s, equal to its master row" % golden,
                       "%d message(s) for golden record %s after the merge (waited %d s)%s" % (len(golden_data), golden, self.timeout("REALTIME", 60) + int(self.settle()),
                                                                                                "; fields differing from the master row: %s" % diffs[:4] if diffs else ""))
        events = [e for e in self.events() if L.text(e.get("recordKey")) in ids + [golden]]
        self.rep.info("10", "METRIC merge notifications: golden message = %s; retire notices for the %d merged members = %d of %d; data messages re-sent for them = %d" % (
            "yes" if golden_data else "NO", len(ids), len(retire_keys), len(ids), len(resent)),
            "measured fact, not judged. Expected since lot S2 (2026-10-18): golden yes, a RETIRE notice (`_change`, reason MERGE) for each merged member - before it the members got none (gap measured 2026-09-27). Events: %s" % (
                ", ".join("%s %s %s" % (e.get("recordKey"), e.get("change"), e.get("status")) for e in events) or "none listed"))
        self.details["distribution"]["merge"] = {"source": source, "target": target, "golden": golden, "goldenMessages": len(golden_data), "retireNotices": retire_keys, "membersResent": len(resent),
                                                 "events": [dict(e) for e in events[:10]]}

    # ---- h2. the retire path through an UNMERGE (lot S4)
    def report_missed_pairs(self, meta, rows, suspects):
        """INFO: the true pairs (ground truth) the run did NOT merge - two or more source records of one person still GOLDEN - and which of them the add-on's REST could merge."""
        mapping = M.source_to_app(self.sources)
        records, _unknown, _goldens = M.master_records(rows, mapping, self.truth, M.auto_created_ids(meta))
        pairs = [(L.text(s.get("sourceId")), L.text(s.get("targetId"))) for s in suspects]
        missed = M.missed_true_pairs(records, meta, pairs)
        linked = [m for m in missed if m[2]]
        self.rep.info("10", "METRIC true pairs the run did not merge = %d people with 2+ source records still GOLDEN (%d of them have a record in a suspect pair)" % (len(missed), len(linked)),
                      "; ".join("person %s: records %s%s" % (p, ", ".join(ids), " (suspect: %s)" % ", ".join(e) if e else "") for p, ids, e in missed[:M.LISTING_LIMIT])
                      + (" (+%d more)" % (len(missed) - M.LISTING_LIMIT) if len(missed) > M.LISTING_LIMIT else "")
                      + ("" if missed else "none: every person's records are merged or alone")
                      + ". The add-on's REST merges only the records of ONE group (acceptSuspects on a suspect pair); a true pair that is not a suspect pair has no group, so no route merges it")
        self.details["distribution"]["missedTruePairs"] = {"people": len(missed), "withSuspect": len(linked), "listed": [{"person": p, "records": ids, "suspect": e} for p, ids, e in missed[:M.LISTING_LIMIT]]}

    def dist_unmerge_notices(self, ctx):
        name = "unmerge a golden record: the topic receives notices (the retire of the golden, the members coming back)"
        path = ctx["personPath"]
        topic = self.dist_topics.get(path, "")
        if not ctx["audit"].get(path):
            self.rep.skipped("10", name, "the person topic could not be read above")
            return
        r_meta, meta = self.read_table(self.mds, "RecordMetadata")
        r_susp, suspects = self.read_table(self.mds, "Suspected")
        r_rows, rows = self.master_table_rows(self.person_table)
        self.report_missed_pairs(meta, rows, suspects)
        pick = M.pick_unmerge_golden(meta)
        if pick is None:
            self.rep.skipped("10", name, "no golden record created by the add-on with merged members is left (%d metadata row(s)): there is nothing to unmerge, the retire path is not measured" % len(meta))
            return
        golden, members = pick
        before, err = self.read_topic(topic)
        if err:
            self.rep.failed("10", name, "the topic could not be read before the unmerge: " + err)
            return
        others_before = {}          # the other topics: a business object's unmerge may touch its member tables too (INFO only)
        for other in sorted(self.dist_mappings):
            if other != path:
                o_msgs, o_err = self.read_topic(self.dist_topics.get(other, ""))
                if not o_err:
                    others_before[other] = o_msgs
        attempts, accepted = [], None
        for url in M.unmerge_paths(self.master_space, self.master_dataset, self.person_table, golden):
            resp = self.admin.request("DELETE", url)
            attempts.append("DELETE %s -> HTTP %d%s" % (url.replace("/ebx-addon-mame/rest/v1", ""), resp.status, "" if resp.status in (200, 204) else " " + S.clip(resp.error_text(), 120)))
            if resp.status in (200, 204):
                accepted = url
                break
        if accepted is None:
            self.rep.info("10", "unmerge refused by the add-on's REST (route never seen live before lot S4)", " | ".join(attempts))
            self.rep.skipped("10", name, "the add-on refused DELETE mergingProcesses for golden record %s (%d attempt(s), listed above): the unmerge was not made, the retire path is not measured" % (golden, len(attempts)))
            return
        self.dist_unmerged = True
        self.rep.info("10", "golden record %s (members %s) unmerged by the add-on" % (golden, ", ".join(members)), attempts[-1])

        def fetch_meta():
            return self.read_table(self.mds, "RecordMetadata")[1]

        def deleted(rows_):
            return any(L.text(m.get("functionalId")) == golden and M.state_of(m) == M.STATE_DELETED for m in rows_)
        try:
            meta_now = L.poll(fetch_meta, deleted, self.timeout("UNMERGE", 120), self.interval(), "golden record %s to be DELETED" % golden)
        except L.Timeout:
            meta_now = fetch_meta()
            self.rep.info("10", "the unmerge answered 200 but golden record %s is not DELETED" % golden,
                          "state after %d s: %s" % (self.timeout("UNMERGE", 120), M.STATE_NAMES.get(next((M.state_of(m) for m in meta_now if L.text(m.get("functionalId")) == golden), None), "unknown")))
            self.rep.skipped("10", name, "no unmerge happened, so there is nothing to notify")
            return
        back = {}
        for m in meta_now:
            if L.text(m.get("functionalId")) in members:
                back[L.text(m.get("functionalId"))] = M.state_of(m)
        live_back = sorted((i for i, st in back.items() if st == M.STATE_GOLDEN), key=M.num_key)
        self.rep.info("10", "after the unmerge: golden %s is %s; members %s" % (golden, M.STATE_NAMES.get(M.STATE_DELETED), ", ".join("%s %s" % (i, M.STATE_NAMES.get(back.get(i))) for i in members)),
                      "%d member(s) are GOLDEN again (the Factory re-sends those); the others (PENDING_APPROVAL...) are not sent until they are GOLDEN" % len(live_back))

        def fetch():
            msgs, err_ = self.read_topic(topic)
            return msgs, err_, (M.new_messages(before, msgs) if not err_ else [])

        def complete(v):
            if v[1] is not None:
                return True
            kinds = [(L.text(m.get("key")), M.message_kind(M.message_doc(m.get("value")))) for m in v[2]]
            return (golden, M.KIND_RETIRE) in kinds and all((i, M.KIND_DATA) in kinds for i in live_back)
        try:
            L.poll(fetch, complete, self.timeout("UNMERGETOPIC", 90), self.interval(), "the notices of the unmerge of golden record %s" % golden)
        except L.Timeout:
            pass
        time.sleep(self.settle())
        msgs, err, fresh = fetch()
        if err:
            self.rep.failed("10", name, "the topic could not be read: " + err)
            return
        docs = [(L.text(m.get("key")), M.message_doc(m.get("value")) or {}) for m in fresh]
        retire_golden = [d for k, d in docs if k == golden and M.is_retire_notice(d)]
        upserts = sorted(set(k for k, d in docs if k in members and M.message_kind(d) == M.KIND_DATA), key=M.num_key)
        retire_members = sorted(set(k for k, d in docs if k in members and M.is_retire_notice(d)), key=M.num_key)
        golden_data = [d for k, d in docs if k == golden and M.message_kind(d) == M.KIND_DATA]
        deletes = [k for k, d in docs if M.is_delete_notice(d)]
        others = [k for k, d in docs if k != golden and k not in members]
        events = [e for e in self.events() if L.text(e.get("recordKey")) in members + [golden]]
        waited = self.timeout("UNMERGETOPIC", 90) + int(self.settle())
        self.rep.check("10", name, bool(fresh),
                       "%d new message(s) on %s after the unmerge of golden record %s" % (len(fresh), topic, golden),
                       "nothing arrived on %s within %d s of the unmerge of golden record %s (the golden is DELETED and %d member(s) are GOLDEN again, yet no notice and no record was sent; events: %s)" % (
                           topic, waited, golden, len(live_back), ", ".join("%s %s %s" % (e.get("recordKey"), e.get("change"), e.get("status")) for e in events) or "none listed"))
        reason = ",".join(sorted(set(L.text(d.get("reason")) for d in retire_golden if L.text(d.get("reason"))))) or "none stated"
        self.rep.info("10", "METRIC unmerge notifications: RETIRE of the deleted golden = %d (reason %s); UPSERT for the members back = %d of %d GOLDEN member(s); retire notices for members = %d; "
                            "record message re-sent for the deleted golden = %d; delete notices = %d; other keys = %d" % (
                                len(retire_golden), reason, len(upserts), len(live_back), len(retire_members), len(golden_data), len(deletes), len(set(others))),
                      "measured, not judged (a FAIL only when nothing arrives). Expected per lot S2: RETIRE of the golden with reason UNMERGE, no retiredTo; an UPSERT for each member that is GOLDEN again. Events: %s" % (
                          ", ".join("%s %s %s" % (e.get("recordKey"), e.get("change"), e.get("status")) for e in events) or "none listed"))
        member_tables = {}
        for other, o_before in sorted(others_before.items()):
            o_topic = self.dist_topics.get(other, "")
            o_msgs, o_err = self.read_topic(o_topic)
            if o_err:
                continue
            o_new = [M.message_kind(M.message_doc(m.get("value")) or {}) for m in M.new_messages(o_before, o_msgs)]
            member_tables[M.table_name(other)] = {"newMessages": len(o_new), "records": o_new.count(M.KIND_DATA), "retire": o_new.count(M.KIND_RETIRE), "delete": o_new.count(M.KIND_DELETE)}
            self.rep.info("10", "unmerge: %s received %d new message(s) (%d record, %d retire, %d delete)" % (o_topic, len(o_new), o_new.count(M.KIND_DATA), o_new.count(M.KIND_RETIRE), o_new.count(M.KIND_DELETE)),
                          "the other tables of the target after the unmerge of a golden record of %s (a business object's member tables may follow; measured, not judged)" % self.person_table)
        self.details["distribution"]["unmerge"] = {"golden": golden, "members": members, "route": accepted.replace("/ebx-addon-mame/rest/v1", ""), "membersAfter": dict((i, M.STATE_NAMES.get(st)) for i, st in back.items()),
                                                    "newMessages": len(fresh), "retireOfGolden": len(retire_golden), "retireReason": reason, "membersUpserted": upserts,
                                                    "membersRetired": retire_members, "goldenRecordResent": len(golden_data), "otherTopics": member_tables, "events": [dict(e) for e in events[:10]]}

    # ---- i. stop
    def dist_stop(self):
        r = self.post("/projects/%s/distribution/targets/%s/stop" % (self.code, self.dist_target))
        data = r.json()
        ok = r.status == 200 and isinstance(data, dict) and "stopped" in data
        self.rep.check("10", "stop: the target's sends are stopped", ok, "stopped=%s, %d job(s) cancelled, %d run(s) stopped: %s" % (
            data.get("stopped") if isinstance(data, dict) else "?", len((data or {}).get("jobsCancelled") or []) if isinstance(data, dict) else 0,
            len((data or {}).get("runsStopped") or []) if isinstance(data, dict) else 0, S.clip(L.text((data or {}).get("message")) if isinstance(data, dict) else "", 120)),
            "HTTP %d %s" % (r.status, S.clip(r.error_text())))

    def wind_down_distribution(self):
        """The target stays ARMED while step 11 reads the summary; at the very end it is disarmed, so that a project left in place sends nothing more to the broker."""
        if not self.dist_target or self.dist_disarmed or self.args.get("cleanup"):
            return
        self.dist_disarmed = True
        try:
            r = self.post("/projects/%s/distribution/targets/%s/disarm" % (self.code, self.dist_target))
            row = self.my_target() or {}
            self.rep.check("10", "the target is disarmed at the end of the run (a project left in place sends nothing more)", r.status == 200 and L.text(row.get("state")) == "DISABLED",
                           "state DISABLED", "HTTP %d %s; state %s" % (r.status, S.clip(r.error_text()), L.text(row.get("state"))))
        except Exception as exc:        # noqa: BLE001  (the end of a run must not crash)
            self.rep.failed("10", "the target is disarmed at the end of the run", "%s: %s" % (type(exc).__name__, exc))

    # ------------------------------------------------------------------------------------------------------------------ step 11: the summary
    def step11(self):
        self.rep.info("11", "the summary of the wizard (step 8) is drawn by the wizard itself",
                      "WizardPage renders it from ProjectWizardUserService#buildSummary on the server and no REST route returns it, so the rendered figures are not read; the scenario reads the facts it is "
                      "built from, with the summary's own definitions, and checks them against what it measured")
        project = self.code
        # landing: rows per source, the sum the journey diagram starts from
        landed = 0
        for app, table in sorted(self.tables.items()):
            resp, row = self.ds.get_row("LandingTable", table["id"])
            landed += L.to_int((row or {}).get("lastLoadRows"))
        expected = sum(self.ROWS[a] for a in self.tables)
        self.rep.check("11", "summary, landing zone: %d rows (the sources' files)" % expected, landed == expected, "%d rows in LandingTable.lastLoadRows" % landed, "%d rows, expected %d" % (landed, expected))
        # staging: what the last load of each entity wrote (written + unchanged) is what the staging table holds; nothing rejected
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % project)
        counts = self.details.get("masterCounts") or {}
        off = []
        for e in entities:
            name = L.text(e.get("name"))
            if name in counts and L.text(e.get("status")) in ("ACCEPTED", "APPLIED"):
                shown = L.to_int(e.get("lastLoadWritten")) + L.to_int(e.get("lastLoadUnchanged"))
                if shown != counts[name]["staging"]:
                    off.append("%s: summary %d, staging table %d" % (name, shown, counts[name]["staging"]))
        self.rep.check("11", "summary, staging zone: rows per entity = rows in the staging tables (%d entities)" % len(counts), not off, "every entity's last load matches its table", "; ".join(off))
        resp, rejects = self.ds.select("TransferReject", "./project='%s'" % project)
        open_rejects = [r for r in rejects if not L.truthy(r.get("resolved")) and not L.truthy(r.get("acknowledged"))]
        self.rep.check("11", "summary, open points: no unresolved reject", not open_rejects, "0 open reject(s)", "%d open reject(s): %s" % (len(open_rejects), L.tally_by(open_rejects, "reasonCode")))
        # master and golden: the figures of the master card and the journey, from the business table as the summary reads it and from the metadataset
        r_rows, rows = self.master_table_rows(self.person_table)
        r_meta, meta = self.read_table(self.mds, "RecordMetadata")
        r_susp, suspects = self.read_table(self.mds, "Suspected")
        states = dict((L.text(m.get("functionalId")), M.state_of(m)) for m in meta)
        auto_live = [L.text(m.get("functionalId")) for m in meta if L.text(m.get("autoCreated")).lower() == "true" and M.state_of(m) != M.STATE_DELETED]
        deleted = sum(1 for m in meta if M.state_of(m) == M.STATE_DELETED)
        shown_rows = len(rows) - deleted
        built = counts.get(self.person_table, {}).get("master")
        self.rep.check("11", "summary, master zone: %s rows = rows built + goldens the add-on created" % self.person_table, built is not None and shown_rows == built + len(auto_live),
                       "%d rows (every state but DELETED) = %s built + %d golden(s) created" % (shown_rows, built, len(auto_live)),
                       "%d rows shown (%d in the table, %d DELETED) but %s were built and %d goldens are alive" % (shown_rows, len(rows), deleted, built, len(auto_live)))
        meta_golden = sum(1 for s in states.values() if s == M.STATE_GOLDEN)
        if rows and "Linked_Field_State" in rows[0]:
            on_table = sum(1 for r in rows if M.state_of({"state": r.get("Linked_Field_State")}) == M.STATE_GOLDEN)
            self.rep.check("11", "summary, golden records: the business table's Linked_Field_State agrees with the metadataset (%d)" % meta_golden, on_table == meta_golden,
                           "%d golden rows in both" % on_table, "%d GOLDEN rows by Linked_Field_State (what the summary counts), %d by RecordMetadata" % (on_table, meta_golden))
        else:
            self.rep.skipped("11", "summary, golden records: the business table's Linked_Field_State agrees with the metadataset", "the master table rows carry no Linked_Field_State column")
        pairs = len(suspects) if r_susp.status == 200 else None
        changed = self.dist_merged or self.dist_rt_changed or self.dist_unmerged
        before = (self.details.get("quality") or {}).get("states", {})
        if changed:
            self.rep.info("11", "summary, matching card: %d golden record(s), %s suspect pair(s)" % (meta_golden, pairs), "changed during step 10 (a record was modified%s), so not compared with step 8's figures (%s golden)" % (
                "".join([" and two suspects merged" if self.dist_merged else "", " and a golden record unmerged" if self.dist_unmerged else ""]), before.get("GOLDEN")))
        else:
            self.rep.check("11", "summary, matching card: %d golden record(s) and %s suspect pair(s) are what the run measured" % (meta_golden, pairs),
                           before.get("GOLDEN") == meta_golden and pairs == len(self.suspect_pairs), "same as the metadataset read after the matching run",
                           "golden %s now, %s after the run; suspect pairs %s now, %d after the run" % (meta_golden, before.get("GOLDEN"), pairs, len(self.suspect_pairs)))
        self.summary_targets()
        self.summary_journal()

    def summary_targets(self):
        r, rows = self.targets()
        if not self.args.get("distribute") or not self.dist_target:
            if L.route_missing(r.status, r.json()):
                self.rep.skipped("11", "summary, distribution card: no target (step 10 not run)", "this build has no GET distribution/targets (HTTP %d)" % r.status)
                return
            self.rep.check("11", "summary, distribution card: no target (step 10 not run)", r.status == 200 and not rows, "0 target(s)", "HTTP %d, %d target(s)" % (r.status, len(rows)))
            return
        mine = [t for t in rows if L.text(t.get("id")) == self.dist_target]
        t = mine[0] if mine else {}
        good = bool(mine) and L.text(t.get("mode")) == "REALTIME" and L.text(t.get("state")) == "ARMED" and L.text(t.get("lastStatus")) == "DONE" and L.text(t.get("lastSendAt")) != ""
        self.rep.check("11", "summary, distribution card: the target is listed REALTIME and ARMED (so no 'not armed' open point), last send DONE", good,
                       "%s: %s / %s, last send %s at %s" % (self.dist_target, L.text(t.get("mode")), L.text(t.get("state")), L.text(t.get("lastStatus")), L.text(t.get("lastSendAt"))),
                       "%d target(s) listed; target %s: mode %s, state %s, last status %s, last send '%s'" % (len(rows), self.dist_target, L.text(t.get("mode")), L.text(t.get("state")), L.text(t.get("lastStatus")), L.text(t.get("lastSendAt"))))

    def summary_journal(self):
        """The 'last full run' bars: the latest COMPLETED run (startedAt and endedAt) of each of LANDING_LOAD, STAGING_LOAD, MASTER_LOAD and MATCHING_RUN; a step that never completed has no bar."""
        resp, steps = self.ds.select("PipelineStep", "./project='%s'" % self.code, page_size=500, max_pages=40)
        for step in ("LANDING_LOAD", "STAGING_LOAD", "MASTER_LOAD", "MATCHING_RUN"):
            done = [s for s in steps if L.text(s.get("step")) == step and L.text(s.get("startedAt")) and L.text(s.get("endedAt"))]
            latest = sorted(done, key=lambda s: L.text(s.get("startedAt")))[-1] if done else None
            self.rep.check("11", "summary, last full run: %s has a completed run (a bar in the journal)" % step, latest is not None and L.text(latest.get("status")) == "DONE",
                           "latest %s %s -> %s" % (L.text((latest or {}).get("status")), L.text((latest or {}).get("startedAt")), L.text((latest or {}).get("endedAt"))),
                           "%d PipelineStep row(s) of %s for the project, none completed and DONE (found: %s): the journal would show no bar" % (
                               len([s for s in steps if L.text(s.get("step")) == step]), step, ", ".join(sorted(set("%s/%s" % (L.text(s.get("step")), L.text(s.get("status"))) for s in steps if step in L.text(s.get("step")))))))
        if self.dist_target:
            first = [s for s in steps if L.text(s.get("step")) == "DISTRIBUTION_FIRST_FULL_SEND"]
            self.rep.info("11", "summary, last full run: the first full send is not a bar of the journal", "%d DISTRIBUTION_FIRST_FULL_SEND step(s), latest %s" % (
                len(first), L.text(sorted(first, key=lambda s: L.text(s.get("startedAt")))[-1].get("status")) if first else "none"))

    # ------------------------------------------------------------------------------------------------------------------ cleanup (--cleanup, --cleanup-project)
    def collect_distribution_facts(self):
        """Before the deletion: the run's distribution targets, their mapping ids (the deletion plan names the flows by number) and their topics, read from the instance - so that a
        --cleanup-project, which is a new process, knows them too. The connection of the run has a deterministic id."""
        self.dist_connector = self.dist_connector or ("Kafka" + self.code)
        resp, rows = self.ds.select("DistributionTarget", "./project='%s'" % self.code)
        for t in rows if resp.status == 200 else []:
            for path, mapping in M.parse_pairs(t.get("connectorsMappingIds")).items():
                self.dist_mapping_ids.add(mapping)
            for path, topic in M.parse_pairs(t.get("tableDestinations")).items():
                self.dist_topics.setdefault(path, topic)
        self.dist_targets_before = [L.text(t.get("id")) for t in rows] if resp.status == 200 else []

    def plan_item_owned(self, item):
        if item.get("kind") == "DISTRIBUTION_FLOW":
            return str(item.get("name", "")) in self.dist_mapping_ids
        return S.Scenario.plan_item_owned(self, item)

    def cleanup(self):
        self.collect_distribution_facts()
        try:
            super(DistributionSteps, self).cleanup()
        finally:
            self.cleanup_distribution()
            self.cleanup_topics()

    def project_was_deleted(self):
        return any(c["name"] == "project deleted with its generated objects" and c["status"] == "PASS" for c in self.rep.checks)

    def cleanup_distribution(self):
        """After the project deletion: the distribution rows the Factory's own deletion should have removed (reported as a Factory gap, and deleted here, when it did not), then the
        Connectors connection of the run and its properties - the plan cannot name them (the Factory has no evidence it created them), so they are deleted by Data Services."""
        deleted = self.project_was_deleted()
        for table in ("DistributionTarget", "DistributionEvent"):
            resp, rows = self.ds.select(table, "./project='%s'" % self.code, page_size=200, max_pages=10)
            if resp.status != 200 or not rows:
                continue
            if not deleted:
                continue
            ids = [L.text(r.get("id")) for r in rows]
            self.out.write("note  FACTORY GAP: the project deletion left %d %s row(s) (%s); deleted here by Data Services\n" % (len(ids), table, M.listing(ids, 5)))
            for rid in ids:
                self.guarded_delete(table, rid)
        conn = client.Ds(self.admin, CONNECTORS_SPACE, CONNECTORS_DATASET)
        cid = self.dist_connector
        resp, row = conn.get_row("Connector", cid)
        if resp.status != 200 or row is None:
            return
        if not deleted:
            self.leftovers.append("Connectors connection %s (kafka at %s, created by --distribute): kept, the project deletion did not complete so its mappings may still use it" % (cid, row.get("baseUrl")))
            return
        resp2, props = conn.select("ConnectorProperty", "./connector='%s'" % cid)
        for p in props:
            self.guard.allow_path(conn.record("ConnectorProperty", "%s|%s" % (cid, L.text(p.get("key")))))
            conn.delete("ConnectorProperty", "%s|%s" % (cid, L.text(p.get("key"))))
        self.guard.allow_path(conn.record("Connector", cid))
        r = conn.delete("Connector", cid)
        self.dist_deleted_connector = r.status in (200, 204)
        self.rep.check("C", "Kafka connection %s deleted (Connectors dataset, Data Services)" % cid, self.dist_deleted_connector, "%d property row(s) and the connection" % len(props),
                       "HTTP %d %s: a mapping still uses it, or the right is missing" % (r.status, S.clip(r.error_text(), 200)))
        if not self.dist_deleted_connector:
            self.leftovers.append("Connectors connection %s (kafka at %s): could not be deleted (HTTP %d)" % (cid, row.get("baseUrl"), r.status))

    def cleanup_topics(self):
        """Lot F4 (2026-10-19): after the project deletion, the project's own Kafka topics go too. The candidates are the topics the project's targets named (read before the deletion) and the ones the
        broker lists under the project's prefix; a name is deleted ONLY when it starts with <project code lower case>. (L.project_topics), whatever else the rows or the broker hold. --keep-topics
        leaves them (and LEFT BEHIND says so); a project whose deletion did not complete keeps them too (its targets may still send); a broker that does not answer leaves them with the reason."""
        if self.args.get("keep_topics") or not (self.dist_topics or self.dist_ran) or not self.project_was_deleted():
            return
        named = sorted(set(self.dist_topics.values()))
        own = L.project_topics(self.code, named)
        left = {}
        for topic in named:
            if topic not in own:
                left[topic] = "not deleted: its name does not start with %s" % L.topic_prefix(self.code)
        listed, err = self.topic_lister()()
        if err:
            for topic in own:
                left[topic] = "not deleted: " + err
            self.dist_topics_left = left
            self.rep.skipped("C", "Kafka topics of the run deleted", err)
            return
        present = L.project_topics(self.code, listed)
        gone = [t for t in own if t not in present]
        failed = []
        deleted = []
        deleter = self.topic_deleter()
        for topic in present:
            ok, why = deleter(topic)
            if ok:
                deleted.append(topic)
            else:
                failed.append(topic)
                left[topic] = "not deleted: " + why
        self.dist_topics_left = left
        self.rep.check("C", "Kafka topics of the run deleted (only names starting with %s)" % L.topic_prefix(self.code), not failed,
                       "%d topic(s) deleted%s: %s" % (len(deleted), ", %d already absent" % len(gone) if gone else "", ", ".join(deleted) or "none on the broker"),
                       "%d of %d topic(s) could not be deleted: %s" % (len(failed), len(present), "; ".join("%s (%s)" % (t, left[t]) for t in failed)))

    def guarded_delete(self, table, pk):
        self.guard.allow_path(self.ds.record(table, pk))
        return self.ds.delete(table, pk)

    def distribution_leftovers(self):
        where = self.kafka["bootstrap"]
        if self.dist_topics_left is not None:
            # the cleanup dealt with the topics: only what is still on the broker is left behind, with the reason
            for topic in sorted(self.dist_topics_left):
                self.leftovers.append("Kafka topic %s on %s: %s" % (topic, where, self.dist_topics_left[topic]))
        elif self.dist_topics:
            names = ", ".join(sorted(set(self.dist_topics.values())))
            if self.args.get("keep_topics"):
                self.leftovers.append("Kafka topic(s) %s on %s: kept on the broker (--keep-topics)" % (names, where))
            elif self.args.get("cleanup") or self.args.get("cleanup_project"):
                self.leftovers.append("Kafka topic(s) %s on %s: not deleted (the project deletion did not complete, so the project may still send)" % (names, where))
            else:
                self.leftovers.append("Kafka topic(s) %s on %s: left on the broker with the project (--cleanup-project %s deletes them, or kafka-topics.sh --delete)" % (names, where, self.code))
        if self.dist_ran and not self.dist_deleted_connector and not self.args.get("cleanup") and not self.args.get("cleanup_project"):
            self.leftovers.append("Connectors connection %s (kafka at %s) and distribution target %s (state %s): created by --distribute and left with the project" % (
                self.dist_connector, self.kafka["bootstrap"], self.dist_target or "?", "DISABLED" if self.dist_disarmed else "see GET distribution/targets"))

    def finish(self, t_run, code_exit=0):
        self.wind_down_distribution()
        self.distribution_leftovers()
        return super(DistributionSteps, self).finish(t_run, code_exit)
