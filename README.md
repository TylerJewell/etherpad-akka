# etherpad-akka

Takes two edits made against the same shared document at the same time and produces
one document both edits actually land in — the same converged text no matter which
edit's changes get folded in first.

A port of [ether/etherpad](https://github.com/ether/etherpad) onto **Akka**, built with
**Akka Specify**.

![The document after two people insert text at different points at the same time](docs/images/converged.png)

---

## Where it came from

Etherpad is a collaborative text editor: several people type into the same document at
once and everyone sees one shared result. This port takes the part of it that makes
that possible — turning two edits based on the same starting text into one edit that
carries both, deterministically, whichever one is folded in first — and rebuilds it on
Akka. It was ported to derive a specification format precise enough to regenerate a
system on a different stack — the port is the vehicle, the specification is the
deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `etherpad-port/`.

---

## ether/etherpad → this port

📉 460 TypeScript lines → **497 Java lines**<br>
📁 5 files → **6 files**<br>
⚡ 119,086 → **7,537** nanoseconds per converge operation<br>
🎯 2 of 2 fixed workloads agree → **2 of 2**<br>
🖥️ 0 changed screen regions → **0**<br>
🧪 0 tests → **43 tests**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](bench/REPORT.md).

---

## What it took to build

⏱️ **1.0 hours** from the first command to the published repository, **1.0** of them active<br>
💬 **593** exchanges with the model<br>
✍️ **297,832** tokens written by the model, **155,129,013** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **43** tests

```bash
python toolkit/tokens.py --port etherpad    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A changeset is a document edit, written down.** It says how long the document was
  before, how long after, and which stretches of it were kept, removed, or inserted —
  so an edit can be stored, sent somewhere else, and applied later without needing the
  document itself along with it.
- **Two edits based on the same document can both be kept.** Given edit A and edit B,
  each written against the same starting text, one edit is produced that rewrites B so
  it applies cleanly *after* A already has — and doing the same the other way around,
  starting from B instead, lands on the identical final document either way.
- **A document remembers every edit that produced it, in order.** A new edit that
  arrives late — written against an older version of the document than the one that
  now exists — is rewritten against every edit committed since, one at a time, so it
  still applies.

Generated documentation lives at [`docs/index.html`](docs/index.html) — open it in a
browser for the entity diagram, the interaction path, and the component reference.

---

## Design decisions

**Plain text only, no formatting.** Etherpad's edits can also carry bold, italic, and
author-color spans, tracked through a shared attribute pool alongside the text itself.
This port carries none of that — every edit is either kept, removed, or inserted text
and nothing else. That keeps the one hard part — making two people's edits agree on one
final document — visible on its own, without a second, separate merging problem
(who wins when two people bold the same word) tangled into the same code.

**One document is one unit that says yes or no to an edit at a time.** Two edits
arriving at once for the same document are handled one after the other, in whichever
order they're accepted — never both at once. That's what keeps "which edit landed
first" a real, single-valued question instead of a race with no fixed answer.

**Submitting an edit is a plain web request, not an open connection.** The original
keeps a live connection to each person's browser so it can push other people's
typing to them instantly. This port answers one question — "given this edit against
this version of the document, what's the result?" — over a normal request, because
that is the whole of what the ported slice needs to prove: send it, get the merged
document back.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/etherpad-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9078.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9078**.

### Try it

```bash
curl -X POST http://localhost:9078/pads/demo/edits \
  -H "Content-Type: application/json" \
  -d '{"authorId":"alice","baseRevision":0,"changeset":"Z:1>6=0+6$hello\n"}'

curl http://localhost:9078/pads/demo
curl http://localhost:9078/p/demo/export/html
```

---

## Configuration

Nothing beyond the port number in `application.conf` — this port calls no external
service and stores nothing outside the entity's own event journal.

---

## Where it differs from ether/etherpad

Everything not listed here behaves the same way on purpose, including the parts that
look like mistakes.

- **Formatting (bold, italic, author color) is not carried at all.** The original
  merges formatting spans through a shared attribute pool alongside the text. This
  port's edits are plain text only — not checked, because it was never built, and
  listed rather than left silent.
- **Editing happens over a request, not a live connection.** The original pushes
  every keystroke to every open browser over a socket the moment it happens; a
  document open in two tabs updates without either tab asking. This port answers one
  edit at a time over a plain web request, so nothing is pushed anywhere — a caller
  finds out about somebody else's edit only by asking again.
- **When two edits touch the exact same stretch of text, both survive, concatenated —
  not checked against the original for every possible way that can happen.** The
  original's own rule was read from its source and confirmed by running it once on
  the case this port's tests cover (two edits replacing the same words). Whether that
  holds for every kind of overlap — one edit's replacement partly inside another's,
  for instance — was not separately checked.
- **A resubmitted edit is recognised as the same edit only when it matches
  byte-for-byte and comes from the same caller.** The original ties this to a
  person's live connection, which this port doesn't have; matching on the edit's own
  content and who sent it is this port's own choice for what "the same edit sent
  twice" means without one.

---

## Licence

ether/etherpad is Apache License 2.0, © the Etherpad Foundation and contributors. This
port reimplements the behaviour in Java, translating several of the original's
functions line for line and reusing one of its templates verbatim; see
`ACKNOWLEDGEMENTS.md`.
