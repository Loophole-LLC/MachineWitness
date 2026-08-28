(function () {
  "use strict";

  var heroEl = document.getElementById("hero-art");
  var gridEl = document.getElementById("gallery-grid");
  var bucket = document.body.getAttribute("data-gcs-bucket");

  if (!bucket) {
    heroEl.innerHTML = "<p class=\"empty\">Gallery not configured yet &mdash; set GCS_BUCKET on the site service.</p>";
    return;
  }

  var manifestUrl = bucket === "__local__"
    ? "/manifest.json"
    : "https://storage.googleapis.com/" + bucket + "/manifest.json?t=" + Date.now();

  fetch(manifestUrl, { cache: "no-store" })
    .then(function (response) {
      if (!response.ok) {
        throw new Error("manifest fetch failed: " + response.status);
      }
      return response.json();
    })
    .then(renderGallery)
    .catch(function () {
      heroEl.innerHTML = "<p class=\"empty\">No artwork has been generated yet &mdash; check back after next week's AI news roundup.</p>";
    });

  function renderGallery(manifest) {
    var entries = (manifest && manifest.entries) || [];
    if (entries.length === 0) {
      heroEl.innerHTML = "<p class=\"empty\">No artwork has been generated yet &mdash; check back after next week's AI news roundup.</p>";
      return;
    }

    var latest = entries[0];
    heroEl.innerHTML = renderHero(latest);

    var rest = entries.slice(1);
    if (rest.length === 0) {
      gridEl.innerHTML = "<p class=\"empty\">This is the first piece &mdash; the archive will grow with each new week.</p>";
      return;
    }
    gridEl.innerHTML = rest.map(renderCard).join("");
  }

  function renderHero(entry) {
    return (
      "<figure class=\"hero-figure\">" +
      "<a href=\"" + escapeAttr(entry.imageUrl) + "\" target=\"_blank\" rel=\"noopener\">" +
      "<img src=\"" + escapeAttr(entry.imageUrl) + "\" alt=\"Generative artwork for AI news week " + escapeAttr(entry.version) + "\" fetchpriority=\"high\" decoding=\"async\" />" +
      "</a>" +
      "<figcaption>" +
      "<p class=\"version-line\">" + escapeHtml(entry.version) + " &middot; " + escapeHtml(entry.date || "") + "</p>" +
      "<p class=\"prompt\">" + escapeHtml(entry.prompt || "") + "</p>" +
      renderRationale(entry) +
      renderHighlights(entry) +
      "<p class=\"source-line\"><a href=\"" + escapeAttr(entry.sourceUrl || "#") + "\" target=\"_blank\" rel=\"noopener\">Explore this week's AI news sources &#8599;</a></p>" +
      "</figcaption>" +
      "</figure>"
    );
  }

  function renderRationale(entry) {
    if (!entry.rationale) {
      return "";
    }
    return (
      "<blockquote class=\"rationale\">" +
      "<p class=\"rationale-label\">Why I made this</p>" +
      "<p class=\"rationale-text\">" + escapeHtml(entry.rationale) + "</p>" +
      "</blockquote>"
    );
  }

  function renderHighlights(entry) {
    var highlights = entry.highlights || [];
    if (highlights.length === 0) {
      return "";
    }
    var items = highlights.map(function (h) {
      return "<li>" + escapeHtml(h) + "</li>";
    }).join("");
    return "<details class=\"highlights\"><summary>This week's headlines</summary><ul>" + items + "</ul></details>";
  }

  function renderCard(entry) {
    return (
      "<a class=\"card\" href=\"" + escapeAttr(entry.imageUrl) + "\" target=\"_blank\" rel=\"noopener\">" +
      "<img src=\"" + escapeAttr(entry.imageUrl) + "\" alt=\"Generative artwork for AI news week " + escapeAttr(entry.version) + "\" loading=\"lazy\" />" +
      "<span class=\"card-caption\">" + escapeHtml(entry.version) + " &middot; " + escapeHtml(entry.date || "") + "</span>" +
      "</a>"
    );
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function escapeAttr(value) {
    return escapeHtml(value).replace(/"/g, "&quot;");
  }
})();
