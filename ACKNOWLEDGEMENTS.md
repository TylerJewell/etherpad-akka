# Acknowledgements

This project is a port of **[ether/etherpad](https://github.com/ether/etherpad)**
(measured at commit `d4d7a61a4e77b586b47d02cbb708c3a143d50e45`).

## Licence

ether/etherpad's `LICENSE` file is the Apache License, Version 2.0. Nothing in this
port's own code is placed under a different licence than what it derives from; where a
file is copied or closely derived (below), it is Apache-2.0 by inheritance.

## Copied verbatim

- **`etherpad-akka/src/main/resources/export_html.html`** is
  `ether/etherpad`'s `src/templates/export_html.html`, byte for byte, with its two EJS
  placeholders (`<%- padId %>`, `<%- body %>`) replaced by `{{PAD_ID}}`/`{{BODY}}`
  tokens — this port has no EJS engine. Reused rather than rebuilt per RENDERING.md R3;
  see `gui/manifest.json`.
- **Error and assertion messages in `Changeset.java`/`ChangesetCodec.java`** are the
  literal strings `Changeset.ts` throws, copied on purpose so a caller (and this port's
  own tests, e.g. `ChangesetTest#checkRepRejectsShortBank`) sees the same wording the
  source does: `"Invalid changeset: not enough chars in charBank"`, `"Invalid
  changeset: excess characters in the charBank"`, `"Invalid changeset: not in canonical
  form"`, `"Invalid changeset: claimed length does not match actual length"`,
  `"Invalid changeset: multiline insert op does not end with a newline"`, `"Invalid
  changeset: number of newlines in insert op does not match the charBank"`, `"Invalid
  changeset: Unknown opcode: "`, `"Not a changeset: "`, `"invalid operation: "`,
  `"mismatched apply: "`, `"mismatched composition of two changesets"`, `"mismatched
  follow - cannot transform cs1 on top of cs2"`, `"newline count is wrong in op +/-/=;
  cs:"`, `"unexpected opcode in op: "`.
- **The changeset header regex**, `Z:([0-9a-z]+)([><])([0-9a-z]+)` in
  `ChangesetCodec.java`, is `Changeset.ts`'s `unpack`'s own `headerRegex`, copied
  because the wire format this port reads and writes is the source's own format —
  producing a different regex for the same grammar would be pointless divergence, not
  independence.
- **The algorithms** in `Changeset.java` (`applyToText`, `checkRep`, `compose`,
  `follow`, `slicerZipperFunc`, `applyZip`) and the assembler classes (`Op.java`,
  `OpAssembler.java`, `MergingOpAssembler.java`, `SmartOpAssembler.java`) are line-for-line
  translations of `Changeset.ts`/`Op.ts`/`OpAssembler.ts`/`MergingOpAssembler.ts`/`SmartOpAssembler.ts`
  into Java, with the attribute-pool parameters and logic removed (see SPEC-001 §4 —
  attribute spans are out of this port's scope). `bench/REPORT.md` §3 lists the exact
  symbols and their line counts on both sides.

## Coincidental, not copied

`python toolkit/copied_strings.py etherpad --source etherpad-src` also flagged five
short strings shared with the source that are not copies: `' and text:'` and `' to
document of length '` (both sides build a similar diagnostic sentence independently),
`'still open'` and `'unreachable'` (common English words that also appear somewhere in
the source's ~23,000 lines), and `'export_html.html'` (the filename itself, which
necessarily matches the file it names).

## Behaviour derived without text being copied

The changeset wire format itself (`Z:<oldLen><sign><delta><ops>$<bank>`, the op
encoding, the base-36 numbers) is derived from the source even where no string was
copied — it's a format this port reads and writes to remain a valid Etherpad
changeset, not an original design. The entity's rebase loop (SPEC-001 R6) is a
translation of the loop shape in `PadMessageHandler.ts:964-981`, not a novel algorithm.

## Also used

- Akka
