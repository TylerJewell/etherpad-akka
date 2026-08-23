package io.akka.etherpad.domain;

/** Concatenates ops' wire encoding verbatim. Ported from ether/etherpad {@code OpAssembler.ts}. */
final class OpAssembler {
  private final StringBuilder out = new StringBuilder();

  void append(Op op) {
    out.append(op.toString());
  }

  void clear() {
    out.setLength(0);
  }

  @Override
  public String toString() {
    return out.toString();
  }
}
