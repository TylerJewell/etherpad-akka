package io.akka.etherpad.domain;

/** Consumes a string from the front, tracking position. Ported from ether/etherpad's internal string iterator used by {@code applyToText}/{@code compose}/{@code follow}. */
final class StringIterator {
  private final String s;
  private int curIndex = 0;

  StringIterator(String s) {
    this.s = s;
  }

  String peek(int n) {
    int end = Math.min(curIndex + n, s.length());
    return s.substring(curIndex, end);
  }

  String take(int n) {
    String taken = peek(n);
    curIndex += taken.length();
    return taken;
  }

  void skip(int n) {
    curIndex += n;
  }

  int remaining() {
    return s.length() - curIndex;
  }
}
