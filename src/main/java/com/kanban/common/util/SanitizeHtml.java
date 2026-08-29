package com.kanban.common.util;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/**
 * Port of the sanitize-html configuration used by the comment DTOs: allowed tags,
 * per-tag allowed attributes, http/https/mailto schemes, and forced
 * {@code target="_blank" rel="noopener noreferrer"} on anchors. Disallowed tags are
 * dropped but their text content is kept (sanitize-html default), except for
 * script/style/textarea/option whose content is discarded.
 */
public final class SanitizeHtml {
  private SanitizeHtml() {}

  private static final Set<String> ALLOWED_TAGS = Set.of(
      "p", "br", "b", "i", "em", "strong", "a", "ul", "ol", "li", "blockquote", "code", "pre",
      "h1", "h2", "h3", "span");
  private static final Map<String, List<String>> ALLOWED_ATTRS = Map.of(
      "a", List.of("href", "target", "rel"),
      "span", List.of("class", "data-mention-id", "data-mention"));
  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto");
  private static final Set<String> NON_TEXT_TAGS = Set.of("script", "style", "textarea", "option");
  private static final Set<String> SELF_CLOSING = Set.of("br");

  public static String sanitize(String dirty) {
    if (dirty == null) {
      return null;
    }
    Document doc = Jsoup.parse(dirty, "", Parser.htmlParser());
    doc.outputSettings().escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false);
    StringBuilder out = new StringBuilder();
    for (Node child : doc.body().childNodes()) {
      render(child, out);
    }
    return out.toString();
  }

  private static void render(Node node, StringBuilder out) {
    if (node instanceof TextNode text) {
      out.append(escapeText(text.getWholeText()));
      return;
    }
    if (!(node instanceof Element el)) {
      return; // comments, doctype etc. are dropped
    }
    String tag = el.tagName().toLowerCase();
    if (!ALLOWED_TAGS.contains(tag)) {
      if (!NON_TEXT_TAGS.contains(tag)) {
        for (Node child : el.childNodes()) {
          render(child, out);
        }
      }
      return;
    }
    out.append('<').append(tag);
    List<String> allowed = ALLOWED_ATTRS.getOrDefault(tag, List.of());
    for (Attribute attr : el.attributes()) {
      String name = attr.getKey().toLowerCase();
      if (!allowed.contains(name)) {
        continue;
      }
      if ("a".equals(tag) && ("target".equals(name) || "rel".equals(name))) {
        continue; // forced below
      }
      String value = attr.getValue();
      if ("href".equals(name) && !schemeAllowed(value)) {
        continue;
      }
      out.append(' ').append(name).append("=\"").append(escapeAttr(value)).append('"');
    }
    if ("a".equals(tag)) {
      out.append(" target=\"_blank\" rel=\"noopener noreferrer\"");
    }
    if (SELF_CLOSING.contains(tag)) {
      out.append(" />");
      return;
    }
    out.append('>');
    for (Node child : el.childNodes()) {
      render(child, out);
    }
    out.append("</").append(tag).append('>');
  }

  private static boolean schemeAllowed(String href) {
    String v = href.trim().toLowerCase();
    // sanitize-html: relative URLs (no scheme) are allowed
    int colon = v.indexOf(':');
    if (colon < 0) {
      return true;
    }
    String scheme = v.substring(0, colon);
    if (!scheme.matches("^[a-z][a-z0-9+.-]*$")) {
      return true; // e.g. "foo/bar:baz" → relative
    }
    return ALLOWED_SCHEMES.contains(scheme);
  }

  private static String escapeText(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String escapeAttr(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
