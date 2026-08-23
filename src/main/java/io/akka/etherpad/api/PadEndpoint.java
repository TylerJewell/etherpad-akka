package io.akka.etherpad.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.etherpad.application.PadEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The one capability this port exposes outside a test: submit an edit against a pad,
 * see the result. SPEC-001 §1.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class PadEndpoint {

  public record SubmitRequest(String authorId, int baseRevision, String changeset) {}

  private final ComponentClient componentClient;

  public PadEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/pads/{padId}/edits")
  public PadEntity.EditResult submitEdit(String padId, SubmitRequest request) {
    return componentClient
        .forEventSourcedEntity(padId)
        .method(PadEntity::submitEdit)
        .invoke(new PadEntity.SubmitEdit(
            request.authorId(), request.baseRevision(), request.changeset()));
  }

  @Get("/pads/{padId}")
  public PadEntity.PadView get(String padId) {
    return componentClient.forEventSourcedEntity(padId).method(PadEntity::get).invoke();
  }

  /**
   * The source's own {@code export/html} screen (RENDERING.md R3, R7;
   * `gui/manifest.json`) — server-rendered, no attribute-driven styling, so it's the one
   * screen the source ships that shows exactly this slice's state (the converged
   * document text) with nothing this port doesn't also have. Reused verbatim
   * (`export_html.html` is `ether/etherpad`'s `src/templates/export_html.html`, byte for
   * byte except the two EJS placeholders); only {@code renderBody} — the per-line
   * HTML-escape-and-join a plain-text pad's `<body>` reduces to — was ported, since the
   * source's own version (`ExportHtml.ts`) branches on attribute-driven formatting this
   * port doesn't carry.
   */
  @Get("/p/{padId}/export/html")
  public HttpResponse exportHtml(String padId) {
    var view = componentClient.forEventSourcedEntity(padId).method(PadEntity::get).invoke();
    var shell = resource("export_html.html")
        .replace("{{PAD_ID}}", padId)
        .replace("{{BODY}}", renderBody(view.text()));
    return HttpResponse.create()
        .withStatus(200)
        .withEntity(HttpEntities.create(ContentTypes.TEXT_HTML_UTF8, shell.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Ported from observed behaviour of the real {@code export/html} route (question log):
   * each line HTML-escaped, each followed by its own {@code <br>}, no separator between
   * lines — including the trailing line the document's own final newline produces.
   */
  private static String renderBody(String text) {
    String withoutTrailingNewline = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    StringBuilder out = new StringBuilder();
    for (String line : withoutTrailingNewline.split("\n", -1)) {
      out.append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("<br>");
    }
    return out.toString();
  }

  private static String resource(String path) {
    try (InputStream in = PadEndpoint.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("missing resource: " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("missing resource: " + path, e);
    }
  }
}
