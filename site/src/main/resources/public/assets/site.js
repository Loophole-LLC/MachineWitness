(function () {
  "use strict";

  var ARCHIVE_PAGE_SIZE = 12;

  var heroEl = document.getElementById("hero-art");
  var gridEl = document.getElementById("gallery-grid");
  var loadMoreEl = document.getElementById("gallery-load-more");
  var dialogEl = document.getElementById("piece-dialog");
  var dialogBodyEl = document.getElementById("piece-dialog-body");
  var bucket = document.body.getAttribute("data-gcs-bucket");
  var entriesByVersion = {};
  var archiveEntries = [];
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

    entries.forEach(function (entry) {
      entriesByVersion[entry.version] = entry;
    });

    var latest = entries[0];
    heroEl.innerHTML = renderHero(latest);

    archiveEntries = entries.slice(1);
    archiveShown = 0;
    if (archiveEntries.length === 0) {
      gridEl.innerHTML = "<p class=\"empty\">This is the first piece &mdash; the archive will grow with each new week.</p>";
      return;
    }
    renderNextArchivePage();
  }

  function renderNextArchivePage() {
    var end = Math.min(archiveShown + ARCHIVE_PAGE_SIZE, archiveEntries.length);
    var newCards = archiveEntries.slice(archiveShown, end).map(renderCard).join("");
    gridEl.insertAdjacentHTML("beforeend", newCards);
    archiveShown = end;

    var remaining = archiveEntries.length - archiveShown;
    if (remaining <= 0) {
      loadMoreEl.innerHTML = "";
      return;
    }
    loadMoreEl.innerHTML =
      "<button type=\"button\" class=\"load-more\">Load " +
      Math.min(remaining, ARCHIVE_PAGE_SIZE) + " more &mdash; " + remaining + " earlier " +
      (remaining === 1 ? "week" : "weeks") + "</button>";
    loadMoreEl.querySelector(".load-more").addEventListener("click", renderNextArchivePage);
  }

  function renderHero(entry) {
    return (
      "<figure class=\"hero-figure\">" +
      "<a href=\"" + escapeAttr(entry.imageUrl) + "\" target=\"_blank\" rel=\"noopener\">" +
      "<img src=\"" + escapeAttr(entry.imageUrl) + "\" alt=\"Generative artwork for AI news week " + escapeAttr(entry.version) + "\" fetchpriority=\"high\" decoding=\"async\" />" +
      "</a>" +
      "<figcaption>" + renderDetail(entry) + "</figcaption>" +
      "</figure>"
    );
  }

  function renderDetail(entry) {
    return (
      "<p class=\"version-line\">" + escapeHtml(entry.version) + " &middot; " + escapeHtml(entry.date || "") + "</p>" +
      "<p class=\"prompt\">" + escapeHtml(entry.prompt || "") + "</p>" +
      renderRationale(entry) +
      renderHighlights(entry) +
      "<p class=\"source-line\"><a href=\"" + escapeAttr(entry.sourceUrl || "#") + "\" target=\"_blank\" rel=\"noopener\">Explore this week's AI news sources &#8599;</a></p>"
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
      "<button type=\"button\" class=\"card\" data-version=\"" + escapeAttr(entry.version) + "\">" +
      "<img src=\"" + escapeAttr(entry.imageUrl) + "\" alt=\"Generative artwork for AI news week " + escapeAttr(entry.version) + "\" loading=\"lazy\" />" +
      "<span class=\"card-caption\">" + escapeHtml(entry.version) + " &middot; " + escapeHtml(entry.date || "") + "</span>" +
      "</button>"
    );
  }

  if (gridEl) {
    gridEl.addEventListener("click", function (event) {
      var card = event.target.closest(".card");
      if (!card) {
        return;
      }
      var entry = entriesByVersion[card.getAttribute("data-version")];
      if (!entry || !dialogEl) {
        return;
      }
      dialogBodyEl.innerHTML =
        "<a href=\"" + escapeAttr(entry.imageUrl) + "\" target=\"_blank\" rel=\"noopener\">" +
        "<img src=\"" + escapeAttr(entry.imageUrl) + "\" alt=\"Generative artwork for AI news week " + escapeAttr(entry.version) + "\" />" +
        "</a>" +
        renderDetail(entry);
      dialogEl.showModal();
    });
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
    nextPieceEl.textContent = "Next piece expected " + formatCountdown(msRemaining) + " (estimate)";
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
