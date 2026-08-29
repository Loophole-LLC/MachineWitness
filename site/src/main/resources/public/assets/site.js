(function () {
  "use strict";

  var ARCHIVE_PAGE_SIZE = 12;

  var heroEl = document.getElementById("hero-art");
  var gridEl = document.getElementById("gallery-grid");
  var loadMoreEl = document.getElementById("gallery-load-more");
  var dialogEl = document.getElementById("piece-dialog");
  var dialogBodyEl = document.getElementById("piece-dialog-body");
  var bucket = document.body.getAttribute("data-gcs-bucket");
  var piecesByKey = {};
  var archiveItems = [];
  var archiveShown = 0;

  var nextPieceEl = document.getElementById("next-piece");
  if (nextPieceEl) {
    updateNextPieceCountdown();
    setInterval(updateNextPieceCountdown, 60000);
  }

  if (!bucket) {
    heroEl.innerHTML = "<p class=\"empty\">Gallery not configured yet &mdash; set GCS_BUCKET on the site service.</p>";
    return;
  }

  // Cache-bust by minute, not by request: manifest.json is served with a 60s server-side
  // cache lifetime (see GcsGalleryStore), so bucketing to the same window lets every visitor
  // within that minute share one cached fetch instead of each forcing a fresh origin read.
  var cacheBucket = Math.floor(Date.now() / 60000);
  var manifestUrl = bucket === "__local__"
    ? "/manifest.json"
    : "https://storage.googleapis.com/" + bucket + "/manifest.json?v=" + cacheBucket;

  fetch(manifestUrl)
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
    var latestItems = toItems(latest);
    latestItems.forEach(registerPiece);
    heroEl.innerHTML = latestItems.length === 0
      ? "<p class=\"empty\">No artwork has been generated yet &mdash; check back after next week's AI news roundup.</p>"
      : renderShowcase(latest, latestItems);

    archiveItems = [];
    entries.slice(1).forEach(function (entry) {
      toItems(entry).forEach(function (item) {
        registerPiece(item);
        archiveItems.push(item);
      });
    });
    archiveShown = 0;
    if (archiveItems.length === 0) {
      gridEl.innerHTML = "<p class=\"empty\">This is the first week &mdash; the archive will grow with each new week.</p>";
      return;
    }
    renderNextArchivePage();
  }

  /** Flattens one manifest entry's pieces into {entry, piece} items - each piece (one model's
   * take on the week) is its own displayable unit, in both the showcase and the archive. */
  function toItems(entry) {
    return (entry.pieces || []).map(function (piece) {
      return { entry: entry, piece: piece };
    });
  }

  function pieceKey(entry, piece) {
    return entry.version + "|" + piece.artist;
  }

  function registerPiece(item) {
    piecesByKey[pieceKey(item.entry, item.piece)] = item;
  }

  function renderNextArchivePage() {
    var end = Math.min(archiveShown + ARCHIVE_PAGE_SIZE, archiveItems.length);
    var newCards = archiveItems.slice(archiveShown, end).map(renderCard).join("");
    gridEl.insertAdjacentHTML("beforeend", newCards);
    archiveShown = end;

    var remaining = archiveItems.length - archiveShown;
    if (remaining <= 0) {
      loadMoreEl.innerHTML = "";
      return;
    }
    loadMoreEl.innerHTML =
      "<button type=\"button\" class=\"load-more\">Load " +
      Math.min(remaining, ARCHIVE_PAGE_SIZE) + " more &mdash; " + remaining + " earlier " +
      (remaining === 1 ? "piece" : "pieces") + "</button>";
    loadMoreEl.querySelector(".load-more").addEventListener("click", renderNextArchivePage);
  }

  function renderShowcase(entry, items) {
    var tiles = items.map(function (item) {
      return renderShowcaseTile(entry, item.piece);
    }).join("");
    return (
      "<div class=\"showcase-grid\">" + tiles + "</div>" +
      "<p class=\"version-line\">" + escapeHtml(entry.version) + " &middot; " + escapeHtml(entry.date || "") + "</p>" +
      "<p class=\"disclosure\">Gemini, Claude, and ChatGPT each research this week's AI news and write " +
      "their own prompt and rationale independently &mdash; every image is rendered by Gemini's " +
      "image model, so the only variable between them is the opinion, not the medium.</p>" +
      renderHighlights(entry) +
      "<p class=\"source-line\"><a href=\"" + escapeAttr(entry.sourceUrl || "#") + "\" target=\"_blank\" rel=\"noopener\">Explore this week's AI news sources &#8599;</a></p>"
    );
  }

  function renderShowcaseTile(entry, piece) {
    return (
      "<button type=\"button\" class=\"showcase-tile\" data-key=\"" + escapeAttr(pieceKey(entry, piece)) + "\">" +
      "<img src=\"" + escapeAttr(piece.imageUrl) + "\" alt=\"" + escapeAttr(piece.artist) +
      "'s piece for AI news week " + escapeAttr(entry.version) + "\" fetchpriority=\"high\" decoding=\"async\" />" +
      "<span class=\"artist-label\">" + escapeHtml(piece.artist) + "</span>" +
      "</button>"
    );
  }

  function renderCard(item) {
    var entry = item.entry;
    var piece = item.piece;
    return (
      "<button type=\"button\" class=\"card\" data-key=\"" + escapeAttr(pieceKey(entry, piece)) + "\">" +
      "<img src=\"" + escapeAttr(piece.imageUrl) + "\" alt=\"" + escapeAttr(piece.artist) +
      "'s piece for AI news week " + escapeAttr(entry.version) + "\" loading=\"lazy\" />" +
      "<span class=\"card-caption\">" + escapeHtml(entry.version) + " &middot; " + escapeHtml(piece.artist) + "</span>" +
      "</button>"
    );
  }

  function openPieceDialog(item) {
    var entry = item.entry;
    var piece = item.piece;
    dialogBodyEl.innerHTML =
      "<a href=\"" + escapeAttr(piece.imageUrl) + "\" target=\"_blank\" rel=\"noopener\">" +
      "<img src=\"" + escapeAttr(piece.imageUrl) + "\" alt=\"" + escapeAttr(piece.artist) +
      "'s piece for AI news week " + escapeAttr(entry.version) + "\" />" +
      "</a>" +
      "<p class=\"version-line\">" + escapeHtml(entry.version) + " &middot; " + escapeHtml(entry.date || "") +
      " &middot; " + escapeHtml(piece.artist) + "</p>" +
      "<p class=\"prompt\">" + escapeHtml(piece.prompt || "") + "</p>" +
      renderRationale(piece) +
      renderHighlights(entry) +
      "<p class=\"source-line\"><a href=\"" + escapeAttr(entry.sourceUrl || "#") + "\" target=\"_blank\" rel=\"noopener\">Explore this week's AI news sources &#8599;</a></p>";
    dialogEl.showModal();
  }

  function renderRationale(piece) {
    if (!piece.rationale) {
      return "";
    }
    return (
      "<blockquote class=\"rationale\">" +
      "<p class=\"rationale-label\">Why " + escapeHtml(piece.artist) + " made this</p>" +
      "<p class=\"rationale-text\">" + escapeHtml(piece.rationale) + "</p>" +
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

  function handlePieceClick(event) {
    var target = event.target.closest("[data-key]");
    if (!target) {
      return;
    }
    var item = piecesByKey[target.getAttribute("data-key")];
    if (!item) {
      return;
    }
    openPieceDialog(item);
  }

  if (heroEl) {
    heroEl.addEventListener("click", handlePieceClick);
  }
  if (gridEl) {
    gridEl.addEventListener("click", handlePieceClick);
  }

  if (dialogEl) {
    dialogEl.addEventListener("click", function (event) {
      if (event.target === dialogEl) {
        dialogEl.close();
      }
    });
    var closeBtn = dialogEl.querySelector(".dialog-close");
    if (closeBtn) {
      closeBtn.addEventListener("click", function () {
        dialogEl.close();
      });
    }
  }

  // The generator only ever produces a new piece once per ISO week, on whichever day the daily
  // 13:00 UTC scheduler first runs after Monday 00:00 UTC. This estimates that moment plus a
  // couple hours of slack for job runtime and scheduling jitter - deliberately erring late so
  // the countdown doesn't hit zero before the piece actually exists.
  function nextGenerationEstimate() {
    var now = new Date();
    var target = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), 15, 0, 0));
    var daysUntilMonday = (1 - target.getUTCDay() + 7) % 7;
    target.setUTCDate(target.getUTCDate() + daysUntilMonday);
    if (target.getTime() <= now.getTime()) {
      target.setUTCDate(target.getUTCDate() + 7);
    }
    return target;
  }

  function updateNextPieceCountdown() {
    var msRemaining = nextGenerationEstimate().getTime() - Date.now();
    nextPieceEl.textContent = "Next pieces expected " + formatCountdown(msRemaining) + " (estimate)";
  }

  function formatCountdown(ms) {
    if (ms <= 0) {
      return "any time now";
    }
    var totalMinutes = Math.floor(ms / 60000);
    var days = Math.floor(totalMinutes / 1440);
    var hours = Math.floor((totalMinutes % 1440) / 60);
    var minutes = totalMinutes % 60;
    if (days > 0) {
      return "in about " + days + (days === 1 ? " day" : " days") + (hours > 0 ? " " + hours + "h" : "");
    }
    if (hours > 0) {
      return "in about " + hours + (hours === 1 ? " hour" : " hours");
    }
    return "in about " + Math.max(minutes, 1) + (minutes === 1 ? " minute" : " minutes");
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
