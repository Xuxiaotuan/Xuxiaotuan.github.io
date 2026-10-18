"""Isolated MySQL probes; this does not run Flink or a CDC sink."""
import json
import queue
import subprocess
import threading
import uuid
from pathlib import Path

CONTAINER = "cdc-article1-mysql-20261018"
trace = []


class Session:
    def __init__(self, name):
        self.name = name
        self.proc = subprocess.Popen(
            ["docker", "exec", "-i", CONTAINER, "mysql", "-uroot", "--batch",
             "--raw", "--skip-column-names", "--unbuffered"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1,
        )
        self.lines = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            self.lines.put(line.rstrip("\n"))
        self.lines.put(None)

    def sql(self, sql):
        marker = "done_" + uuid.uuid4().hex
        self.proc.stdin.write(sql + ";\nSELECT '" + marker + "';\n")
        self.proc.stdin.flush()
        result = []
        while True:
            line = self.lines.get(timeout=30)
            if line is None or line.startswith("ERROR"):
                raise RuntimeError((self.name, sql, result, line))
            if line == marker:
                break
            result.append(line)
        trace.append({"session": self.name, "sql": sql, "result": result})
        return result

    def close(self):
        self.proc.stdin.close()
        self.proc.wait(timeout=10)


a, b = Session("A"), Session("B")
try:
    version = a.sql("SELECT VERSION(), @@transaction_isolation, @@gtid_mode, @@binlog_format")
    a.sql("CREATE DATABASE article_probe")
    a.sql("CREATE TABLE article_probe.orders (id INT PRIMARY KEY, amount INT NOT NULL, revision INT NOT NULL) ENGINE=InnoDB")
    a.sql("INSERT INTO article_probe.orders VALUES (42,100,1)")
    a.sql("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
    a.sql("START TRANSACTION WITH CONSISTENT SNAPSHOT")
    first = a.sql("SELECT amount,revision FROM article_probe.orders WHERE id=42")
    b.sql("UPDATE article_probe.orders SET amount=200, revision=2 WHERE id=42")
    old_view = a.sql("SELECT amount,revision FROM article_probe.orders WHERE id=42")
    new_view = b.sql("SELECT amount,revision FROM article_probe.orders WHERE id=42")
    a.sql("COMMIT")
    refreshed = a.sql("SELECT amount,revision FROM article_probe.orders WHERE id=42")
    assert first == old_view == ["100\t1"]
    assert new_view == refreshed == ["200\t2"]

    before = b.sql("SHOW BINARY LOG STATUS")
    a.sql("START TRANSACTION")
    a.sql("UPDATE article_probe.orders SET amount=300, revision=3 WHERE id=42")
    during = b.sql("SHOW BINARY LOG STATUS")
    invisible = b.sql("SELECT amount,revision FROM article_probe.orders WHERE id=42")
    a.sql("COMMIT")
    after = b.sql("SHOW BINARY LOG STATUS")
    visible = b.sql("SELECT amount,revision FROM article_probe.orders WHERE id=42")
    assert before == during
    assert after != before
    assert invisible == ["200\t2"] and visible == ["300\t3"]

    a.sql("CREATE TABLE article_probe.sink_model (id INT PRIMARY KEY, amount INT NOT NULL) ENGINE=InnoDB")
    a.sql("CREATE TABLE article_probe.delivery_audit (seq INT AUTO_INCREMENT PRIMARY KEY, row_id INT, amount INT) ENGINE=InnoDB")
    a.sql("INSERT INTO article_probe.sink_model VALUES (42,100)")
    a.sql("CREATE TRIGGER article_probe.audit_delivery AFTER UPDATE ON article_probe.sink_model FOR EACH ROW INSERT INTO article_probe.delivery_audit(row_id,amount) VALUES (NEW.id,NEW.amount)")
    a.sql("UPDATE article_probe.sink_model SET amount=200 WHERE id=42")
    a.sql("UPDATE article_probe.sink_model SET amount=200 WHERE id=42")
    state = b.sql("SELECT amount FROM article_probe.sink_model WHERE id=42")
    attempts = b.sql("SELECT COUNT(*) FROM article_probe.delivery_audit")
    assert state == ["200"] and attempts == ["2"]

    result = {"status": "PASS", "version": version,
              "scope": "Real MySQL sessions; deliberate replay model; no Flink or CDC pipeline",
              "checks": {"repeatable_read": True, "commit_visibility_and_binlog": True,
                         "same_final_value_does_not_prove_single_write": True}, "trace": trace}
    Path(__file__).with_name("mysql-visibility-result.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "trace"}, ensure_ascii=False, indent=2))
finally:
    a.close()
    b.close()
