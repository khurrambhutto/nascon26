"""Self-hosted receipt SMTP sink; messages stay inside the Compose network."""
import base64
import hashlib
import socketserver
from email import policy
from email.parser import BytesParser
from email.utils import parseaddr

from .common import now
from .storage import initialize, transaction

MAX_MESSAGE = 2 * 1024 * 1024


def capture(raw, sender, recipients):
    message = BytesParser(policy=policy.default).parsebytes(raw)
    oid = str(message.get("X-ArenaOne-Order-ID", ""))
    pdfs = [part.get_payload(decode=True) for part in message.iter_attachments()
            if part.get_content_type() == "application/pdf"]
    if not oid or len(pdfs) != 1:
        raise ValueError("Only ArenaOne PDF receipts are accepted")
    with transaction() as db:
        order = db.get("order", oid)
        job = db.get("receipt_email", oid)
        if not order or not job or job["recipient"].casefold() not in {r.casefold() for r in recipients}:
            raise ValueError("Unknown receipt recipient")
        if pdfs[0] != base64.b64decode(order["response"]["receipt"]["pdf_base64"]):
            raise ValueError("Receipt attachment does not match the paid order")
        mid = hashlib.sha256((oid + "|" + job["recipient"].casefold()).encode()).hexdigest()
        if db.get("local_mail", mid):
            return mid
        body = message.get_body(preferencelist=("plain",))
        db.put("local_mail", mid, {"message_id": mid, "order_id": oid,
            "customer_id": order["request"]["customer_id"], "tenant_id": order["tenant_id"],
            "sender": sender, "recipient": job["recipient"], "subject": str(message.get("Subject", "Receipt")),
            "body": body.get_content() if body else "", "received_at": now(),
            "pdf_base64": base64.b64encode(pdfs[0]).decode()})
        return mid


class SMTPHandler(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(20)
        self.wfile.write(b"220 ArenaOne local receipt inbox ESMTP\r\n")
        sender, recipients = None, []
        while True:
            try:
                raw = self.rfile.readline(2049)
            except (TimeoutError, ConnectionError):
                return
            if not raw:
                return
            if len(raw) > 2048:
                self.wfile.write(b"500 Command too long\r\n")
                return
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            command, _, value = line.partition(" ")
            command = command.upper()
            if command in ("EHLO", "HELO"):
                self.wfile.write(b"250-arenaone-local\r\n250 SIZE 2097152\r\n")
            elif command == "MAIL" and value.upper().startswith("FROM:"):
                path = value[5:].strip().split(" ", 1)[0]
                sender = parseaddr(path)[1]
                recipients = []
                self.wfile.write(b"250 OK\r\n" if sender else b"501 Invalid sender\r\n")
            elif command == "RCPT" and value.upper().startswith("TO:"):
                recipient = parseaddr(value[3:].strip())[1]
                if sender and recipient and len(recipients) < 10:
                    recipients.append(recipient)
                    self.wfile.write(b"250 OK\r\n")
                else:
                    self.wfile.write(b"503 Valid sender and recipient required\r\n")
            elif command == "DATA":
                if not sender or not recipients:
                    self.wfile.write(b"503 Sender and recipient required\r\n")
                    continue
                self.wfile.write(b"354 End message with a single dot\r\n")
                chunks, size = [], 0
                while True:
                    try:
                        chunk = self.rfile.readline(MAX_MESSAGE + 1)
                    except (TimeoutError, ConnectionError):
                        return
                    if not chunk:
                        return
                    if chunk == b".\r\n":
                        break
                    size += len(chunk)
                    if size > MAX_MESSAGE:
                        self.wfile.write(b"552 Message too large\r\n")
                        return
                    chunks.append(chunk[1:] if chunk.startswith(b"..") else chunk)
                try:
                    capture(b"".join(chunks), sender, recipients)
                    self.wfile.write(b"250 Receipt saved locally\r\n")
                except ValueError:
                    self.wfile.write(b"550 Valid paid receipt required\r\n")
                except Exception:
                    self.wfile.write(b"451 Local inbox temporarily unavailable\r\n")
                sender, recipients = None, []
            elif command == "RSET":
                sender, recipients = None, []
                self.wfile.write(b"250 OK\r\n")
            elif command == "NOOP":
                self.wfile.write(b"250 OK\r\n")
            elif command == "QUIT":
                self.wfile.write(b"221 Bye\r\n")
                return
            else:
                self.wfile.write(b"502 Command not supported\r\n")


class SMTPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def run():
    initialize()
    with SMTPServer(("0.0.0.0", 1025), SMTPHandler) as server:
        server.serve_forever()


if __name__ == "__main__":
    run()
