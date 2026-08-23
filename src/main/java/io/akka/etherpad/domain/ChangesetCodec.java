package io.akka.etherpad.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The changeset wire format: {@code Z:<oldLen><sign><delta><ops>$<bank>}, base-36
 * numbers, ops encoded as {@code (|<lines>)?<opcode><chars>} with no separators between
 * consecutive ops. Ported from ether/etherpad {@code src/static/js/Changeset.ts}
 * {@code pack}/{@code unpack}/{@code deserializeOps} (attribute prefixes dropped — see
 * SPEC-001 §4, attribute spans are out of this port's slice).
 */
public final class ChangesetCodec {
  private ChangesetCodec() {}

  // Attribute prefixes ("*3*1c") are never emitted by this port, but a changeset built
  // by a caller reusing the real format could still carry them; skip past them rather
  // than fail to parse.
  private static final Pattern OP_PATTERN =
      Pattern.compile("(?:\\*[0-9a-z]+)*(?:\\|([0-9a-z]+))?([-+=])([0-9a-z]+)");
  private static final Pattern HEADER_PATTERN = Pattern.compile("Z:([0-9a-z]+)([><])([0-9a-z]+)");

  public static int parseNum(String s) {
    return Integer.parseInt(s, 36);
  }

  public static String numToString(int n) {
    return Integer.toString(n, 36);
  }

  public record Unpacked(int oldLen, int newLen, String ops, String charBank) {}

  public static Unpacked unpack(String cs) {
    Matcher m = HEADER_PATTERN.matcher(cs);
    if (!m.lookingAt()) throw new IllegalArgumentException("Not a changeset: " + cs);
    int oldLen = parseNum(m.group(1));
    int sign = m.group(2).equals(">") ? 1 : -1;
    int mag = parseNum(m.group(3));
    int newLen = oldLen + sign * mag;
    int opsStart = m.end();
    int opsEnd = cs.indexOf('$');
    if (opsEnd < 0) opsEnd = cs.length();
    return new Unpacked(oldLen, newLen, cs.substring(opsStart, opsEnd), cs.substring(opsEnd + 1));
  }

  public static String pack(int oldLen, int newLen, String opsStr, String bank) {
    int lenDiff = newLen - oldLen;
    String lenDiffStr = lenDiff >= 0 ? ">" + numToString(lenDiff) : "<" + numToString(-lenDiff);
    return "Z:" + numToString(oldLen) + lenDiffStr + opsStr + "$" + bank;
  }

  public static List<Op> deserializeOps(String ops) {
    List<Op> result = new ArrayList<>();
    Matcher m = OP_PATTERN.matcher(ops);
    int pos = 0;
    while (pos < ops.length() && m.find(pos) && m.start() == pos) {
      Op op = new Op(ops.charAt(m.start(2)));
      op.lines = m.group(1) == null ? 0 : parseNum(m.group(1));
      op.chars = parseNum(m.group(3));
      result.add(op);
      pos = m.end();
    }
    if (pos != ops.length()) {
      throw new IllegalArgumentException("invalid operation: " + ops.substring(pos));
    }
    return result;
  }
}
