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
  var latestVersion = null;
  var filteredArchive = [];
  var latestItems = [];
  var dialogItems = [];
  var activePiece = null;
  var filterEl = document.getElementById("artist-filter");
  var archiveCountEl = document.getElementById("archive-count");
  var copyBtn = document.getElementById("copy-piece-link");

  var nextPieceEl = document.getElementById("next-piece");
  if (nextPieceEl) {
    updateNextPieceCountdown();
    setInterval(updateNextPieceCountdown, 60000);
  }

  function loadGallery() {
    heroEl.setAttribute("aria-busy", "true");
    heroEl.innerHTML = '<p class="loading">Loading the latest collection&hellip;</p>';
    if (!bucket || bucket.indexOf("{{") === 0) {
      showGalleryError();
      return;
    }
    // Share the public manifest's one-minute cache across visitors.
    var manifestUrl = bucket === "__local__" ? "/manifest.json" :
      "https://storage.googleapis.com/" + bucket + "/manifest.json?v=" + Math.floor(Date.now() / 60000);
    fetch(manifestUrl)
      .then(function (response) {
        if (!response.ok) throw new Error("manifest fetch failed: " + response.status);
        return response.json();
      })
      .then(renderGallery)
      .catch(showGalleryError)
      .finally(function () { heroEl.setAttribute("aria-busy", "false"); });
  }

  function showGalleryError() {
    heroEl.setAttribute("aria-busy", "false");
    heroEl.innerHTML = '<div class="empty"><p>The gallery could not load. Please try again.</p>' +
      '<button type="button" class="retry-button">Try again</button></div>';
    heroEl.querySelector(".retry-button").addEventListener("click", loadGallery);
    archiveCountEl.textContent = "The archive will appear when the gallery reconnects.";
  }

  loadGallery();

  function renderGallery(manifest) {
    var entries = (manifest && manifest.entries) || [];
    if (entries.length === 0) {
      heroEl.innerHTML = "<p class=\"empty\">No artwork has been generated yet &mdash; check back after next week's AI news roundup.</p>";
      archiveCountEl.textContent = "Earlier collections will appear here after the first week.";
      return;
    }

    var latest = entries[0];
    latestItems = toItems(latest);
    latestItems.forEach(registerPiece);
    heroEl.innerHTML = latestItems.length === 0
      ? "<p class=\"empty\">No artwork has been generated yet &mdash; check back after next week's AI news roundup.</p>"
      : renderShowcase(latest, latestItems);
    if (latestItems.length > 0) {
      injectStructuredData(latest, latestItems);
    }
    document.getElementById("latest-meta").textContent = latest.version + " / " + latestItems.length +
      (latestItems.length === 1 ? " piece" : " pieces");
    latestVersion = latest.version;
    updateNextPieceCountdown();

    archiveItems = [];
    entries.slice(1).forEach(function (entry) {
      toItems(entry).forEach(function (item) {
        registerPiece(item);
        archiveItems.push(item);
      });
    });
    var artists = Array.from(new Set(archiveItems.map(function (item) { return item.piece.artist; })));
    filterEl.innerHTML = '<option value="">All models</option>' + artists.map(function (artist) {
      return '<option value="' + escapeAttr(artist) + '">' + escapeHtml(artist) + '</option>';
    }).join("");
    filterEl.disabled = artists.length === 0;
    filterArchive();
    openPieceFromHash();
  }

  filterEl.addEventListener("change", filterArchive);

  function filterArchive() {
    filteredArchive = archiveItems.filter(function (item) {
      return !filterEl.value || item.piece.artist === filterEl.value;
    });
    archiveShown = 0;
    gridEl.innerHTML = "";
    loadMoreEl.innerHTML = "";
    if (!filteredArchive.length) {
      gridEl.innerHTML = '<p class="empty">The archive will grow with each new week.</p>';
    }
    renderNextArchivePage();
  }

  /** Opens whichever piece the URL fragment names, if it names one we have. */
  function openPieceFromHash() {
    var slug;
    try { slug = decodeURIComponent(String(location.hash || "").replace(/^#/, "")); }
    catch (error) { return false; }
    if (!slug) {
      return false;
    }
    var item = piecesByKey[slug];
    if (!item) {
      return false;
    }
    if (!dialogEl.open || activePiece !== item) {
      dialogItems = toItems(item.entry).map(function (sibling) {
        return piecesByKey[pieceKey(sibling.entry, sibling.piece)];
      });
      openPieceDialog(item, false);
    }
    return true;
  }

  /** Flattens one manifest entry's pieces into {entry, piece} items - each piece (one model's
   * take on the week) is its own displayable unit, in both the showcase and the archive. */
  function toItems(entry) {
    return (entry.pieces || []).map(function (piece) {
      return { entry: entry, piece: piece };
    });
  }

  /** Doubles as the element's data-key, the piecesByKey index, and the URL fragment. */
  function pieceKey(entry, piece) {
    return pieceSlug(entry, piece);
  }

  function registerPiece(item) {
    piecesByKey[pieceKey(item.entry, item.piece)] = item;
  }

  function renderNextArchivePage() {
    var previousCount = archiveShown;
    var end = Math.min(archiveShown + ARCHIVE_PAGE_SIZE, filteredArchive.length);
    gridEl.insertAdjacentHTML("beforeend", filteredArchive.slice(archiveShown, end).map(renderCard).join(""));
    archiveShown = end;
    archiveCountEl.textContent = archiveItems.length ? "Showing " + archiveShown + " of " + filteredArchive.length +
      " earlier pieces" + (filterEl.value ? " by " + filterEl.value : " across all models") + "." :
      "The first collection is on display above. More to come.";
    var remaining = filteredArchive.length - archiveShown;
    loadMoreEl.innerHTML = remaining > 0 ? '<button type="button" class="load-more">Load ' +
      Math.min(remaining, ARCHIVE_PAGE_SIZE) + ' more pieces <span aria-hidden="true">&darr;</span></button>' : "";
    if (remaining > 0) loadMoreEl.querySelector("button").addEventListener("click", renderNextArchivePage);
    // Move focus to the first newly revealed piece when the paging control is replaced.
    if (previousCount > 0) gridEl.children[previousCount].focus({ preventScroll: true });
  }

  function renderShowcase(entry, items) {
    return '<div class="showcase-grid">' + items.map(function (item) {
      return renderShowcaseTile(entry, item.piece);
    }).join("") + '</div>' +
      '<div class="collection-note"><p>News from ' + escapeHtml(formatDateRange(entry.date)) +
      '. All images rendered by Gemini.</p><a href="#about-full">About the experiment &nearr;</a></div>' +
      renderHighlights(entry);
  }

  function formatDateRange(value) {
    return String(value || "this week").replace(/(\d{4})-(\d{2})-(\d{2})/g, function (match, year, month, day) {
      return new Date(Date.UTC(+year, +month - 1, +day)).toLocaleDateString("en-US", {
        month: "short", day: "numeric", year: "numeric", timeZone: "UTC"
      });
    }).replace(" to ", " – ");
  }

  /** One CSS class per model (see the --artist-* colors in styles.css) so its border and label
   * are colored consistently everywhere its image appears. */
  function artistClass(artist) {
    return "artist-" + String(artist || "").toLowerCase().replace(/[^a-z0-9]/g, "");
  }

  /** Falls back to the generic provider name for any piece published before per-piece model
   * versions existed, so a stale cached entry never renders as literally "undefined". */
  function modelLabel(piece) {
    return piece.model || piece.artist;
  }

  /** The grid-sized JPEG, falling back to the full PNG for entries published before thumbnails
   * existed - the archive goes back further than this feature does. */
  function thumbnailUrl(piece) {
    return piece.thumbnailUrl || piece.imageUrl;
  }

  /** This piece's permalink fragment. Must match the slug RssFeed builds server-side, so a feed
   * item's link opens the very piece it describes. */
  function pieceSlug(entry, piece) {
    return entry.version + "-" + String(piece.artist || "").toLowerCase().replace(/[^a-z0-9]/g, "");
  }

  function pieceAltText(entry, piece) {
    return modelLabel(piece) + "'s AI-generated art reacting to AI industry news, week " +
      entry.version + " (" + (entry.date || "") + ")";
  }

  /** Emits schema.org VisualArtwork JSON-LD for the current week's pieces, so crawlers that
   * render JS (Google among them) can index each piece as a distinct, described artwork instead
   * of just an <img> tag. Only covers the latest week - see feed.xml for the full archive, which
   * every crawler can read regardless of whether it executes JS. */
  function injectStructuredData(entry, items) {
    var artworks = items.map(function (item, i) {
      return {
        "@type": "VisualArtwork",
        "position": i + 1,
        "name": modelLabel(item.piece) + "'s take on AI news, week " + entry.version,
        "image": item.piece.imageUrl,
        "creator": { "@type": "Organization", "name": modelLabel(item.piece) },
        "dateCreated": entry.generatedAt || undefined,
        "description": item.piece.rationale,
        "artMedium": "AI-generated digital art",
        "about": "AI industry news, week " + entry.version + (entry.date ? " (" + entry.date + ")" : "")
      };
    });
    var data = {
      "@context": "https://schema.org",
      "@type": "ItemList",
      "name": "Machine Witness — week " + entry.version,
      "itemListElement": artworks
    };
    var existing = document.getElementById("structured-data");
    if (existing) {
      existing.remove();
    }
    var script = document.createElement("script");
    script.id = "structured-data";
    script.type = "application/ld+json";
    // Neutralize "<" so a "</script>"-shaped substring in AI-generated text (e.g. a rationale
    // quoting a headline) can't prematurely close this tag.
    script.textContent = JSON.stringify(data).replace(/</g, "\\u003c");
    document.head.appendChild(script);
  }

  function renderShowcaseTile(entry, piece) {
    return '<button type="button" class="showcase-tile ' + artistClass(piece.artist) + '" data-key="' +
      escapeAttr(pieceKey(entry, piece)) + '" aria-label="View ' + escapeAttr(piece.artist) +
      "'s artwork and explanation, " + escapeAttr(entry.version) + '">' +
      '<img src="' + escapeAttr(thumbnailUrl(piece)) + '" alt="' + escapeAttr(pieceAltText(entry, piece)) +
      '" width="800" height="436" decoding="async" />' +
      '<span class="tile-caption"><span><span class="artist-label">' + escapeHtml(piece.artist) +
      '</span><span class="model-version">' + escapeHtml(modelLabel(piece)) +
      '</span></span><span class="tile-arrow" aria-hidden="true">&nearr;</span></span></button>';
  }

  function renderCard(item) {
    var entry = item.entry;
    var piece = item.piece;
    return '<button type="button" class="card ' + artistClass(piece.artist) + '" data-key="' +
      escapeAttr(pieceKey(entry, piece)) + '" aria-label="View ' + escapeAttr(modelLabel(piece)) +
      "'s artwork and explanation, " + escapeAttr(entry.version) + '">' +
      '<img src="' + escapeAttr(thumbnailUrl(piece)) + '" alt="' + escapeAttr(pieceAltText(entry, piece)) +
      '" width="800" height="436" loading="lazy" decoding="async" />' +
      '<span class="card-caption"><span class="artist-label">' + escapeHtml(piece.artist) +
      '</span><span class="card-week">' + escapeHtml(entry.version) + '</span></span></button>';
  }

  function openPieceDialog(item, pushUrl) {
    var entry = item.entry;
    var piece = item.piece;
    activePiece = item;
    if (pushUrl) {
      history.pushState({ mwPiece: true }, "", "#" + pieceKey(entry, piece));
    }
    var position = dialogItems.indexOf(item);
    var prevBtn = document.getElementById("piece-prev");
    var nextBtn = document.getElementById("piece-next");
    document.getElementById("piece-position").textContent = (position + 1) + " / " + dialogItems.length;
    // Disabling the focused button drops focus to <body>, which is outside the dialog, so the
    // keydown listener below stops seeing anything and the arrow keys die at either end. Hand
    // focus to the opposite button first - it is always enabled when this one is about to not be.
    if (position <= 0 && document.activeElement === prevBtn) nextBtn.focus();
    if (position >= dialogItems.length - 1 && document.activeElement === nextBtn) prevBtn.focus();
    prevBtn.disabled = position <= 0;
    nextBtn.disabled = position >= dialogItems.length - 1;
    copyBtn.textContent = "Copy link";
    document.getElementById("copy-status").textContent = "";
    dialogBodyEl.innerHTML =
      '<figure class="dialog-figure"><a href="' + escapeAttr(piece.imageUrl) +
      '" target="_blank" rel="noopener" aria-label="Open full-resolution artwork in a new tab">' +
      '<img src="' + escapeAttr(piece.imageUrl) + '" alt="' + escapeAttr(pieceAltText(entry, piece)) +
      '" width="1408" height="768" /></a></figure>' +
      '<div class="dialog-content"><h2 id="piece-title">' + escapeHtml(piece.artist) +
      "'s point of view</h2>" + '<p class="dialog-meta">' + escapeHtml(modelLabel(piece)) +
      ' &middot; ' + escapeHtml(entry.version) + ' &middot; ' + escapeHtml(formatDateRange(entry.date)) + '</p>' +
      renderRationale(piece) +
      '<details class="prompt-details"><summary>Read the image prompt</summary><p class="prompt">' +
      escapeHtml(piece.prompt || "No prompt was published for this piece.") + '</p></details>' +
      renderHighlights(entry) +
      '<p class="source-line"><a href="' + escapeAttr(piece.imageUrl) +
      '" target="_blank" rel="noopener">Open full-resolution image &nearr;</a></p>' +
      '<p id="share-fallback" hidden><label for="piece-share-url">Copy this link</label>' +
      '<input id="piece-share-url" type="url" readonly /></p></div>';
    if (!dialogEl.open) dialogEl.showModal();
    dialogEl.scrollTop = 0;
  }

  function movePiece(direction) {
    var next = dialogItems[dialogItems.indexOf(activePiece) + direction];
    if (!next) return;
    // Keep one history entry for a browsing session so Back returns to the collection.
    history.replaceState(history.state, "", "#" + pieceKey(next.entry, next.piece));
    openPieceDialog(next, false);
  }

  document.getElementById("piece-prev").addEventListener("click", function () { movePiece(-1); });
  document.getElementById("piece-next").addEventListener("click", function () { movePiece(1); });
  dialogEl.addEventListener("keydown", function (event) {
    if (/INPUT|TEXTAREA|SELECT/.test(event.target.tagName) || event.altKey || event.ctrlKey || event.metaKey) return;
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      movePiece(event.key === "ArrowLeft" ? -1 : 1);
    }
  });
  copyBtn.addEventListener("click", function () {
    var url = location.href;
    var item = activePiece;
    var copy = navigator.clipboard ? navigator.clipboard.writeText(url) : Promise.reject();
    copy.then(function () {
      if (activePiece !== item) return;
      copyBtn.textContent = "Link copied";
      document.getElementById("copy-status").textContent = "Link copied to clipboard.";
    }).catch(function () {
      if (activePiece !== item) return;
      document.getElementById("share-fallback").hidden = false;
      var input = document.getElementById("piece-share-url");
      input.value = url;
      input.focus();
      input.select();
      document.getElementById("copy-status").textContent = "Select and copy the link below.";
    });
  });

  function renderRationale(piece) {
    if (!piece.rationale) {
      return "";
    }
    return (
      "<blockquote class=\"rationale\">" +
      "<p class=\"rationale-label\">Why " + escapeHtml(modelLabel(piece)) + " made this</p>" +
      "<p class=\"rationale-text\">" + renderRationaleText(piece) + "</p>" +
      "</blockquote>"
    );
  }

  /** Wraps each citation's quote (a short, verbatim substring of the rationale, validated
   * server-side against that week's real feed items) in a link back to the actual headline it's
   * citing, with a hover tooltip naming that headline. Re-validates the substring match here too
   * - a citation the server resolved is still just data, and this is the code that decides where
   * in the DOM it gets spliced in. */
  function renderRationaleText(piece) {
    var text = piece.rationale || "";
    var citations = (piece.citations || []).filter(function (c) {
      return c && c.quote && c.url;
    });
    if (citations.length === 0) {
      return escapeHtml(text);
    }

    var ranges = [];
    citations.forEach(function (c) {
      var start = text.indexOf(c.quote);
      if (start === -1) {
        return;
      }
      var end = start + c.quote.length;
      var overlaps = ranges.some(function (r) {
        return start < r.end && end > r.start;
      });
      if (!overlaps) {
        ranges.push({ start: start, end: end, citation: c });
      }
    });
    if (ranges.length === 0) {
      return escapeHtml(text);
    }
    ranges.sort(function (a, b) {
      return a.start - b.start;
    });

    var html = "";
    var cursor = 0;
    ranges.forEach(function (r) {
      var c = r.citation;
      var tooltip = (c.source ? c.source + ": " : "") + c.headline;
      html += escapeHtml(text.slice(cursor, r.start));
      html += "<a class=\"citation\" href=\"" + escapeAttr(c.url) + "\" target=\"_blank\" rel=\"noopener\" " +
        "title=\"" + escapeAttr(tooltip) + "\">" + escapeHtml(text.slice(r.start, r.end)) + "</a>";
      cursor = r.end;
    });
    html += escapeHtml(text.slice(cursor));
    return html;
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
    dialogItems = event.currentTarget === heroEl ? latestItems : filteredArchive;
    openPieceDialog(item, true);
  }

  if (heroEl) {
    heroEl.addEventListener("click", handlePieceClick);
  }
  if (gridEl) {
    gridEl.addEventListener("click", handlePieceClick);
  }

  if (dialogEl) {
    // Hung on both events on purpose. Escape fires "cancel" and, in Chrome, closes the dialog
    // without ever firing "close" - listening only for the latter left a stale permalink in the
    // address bar, so the next share or reload reopened a piece the reader had dismissed. The
    // close button fires "close" and no "cancel", so neither event alone covers both routes.
    var clearingPieceUrl = false;
    function clearPieceUrl() {
      // Both events can land for one dismissal, and history.back() settles asynchronously, so a
      // second call would still see the old fragment and pop a second entry off the history.
      if (clearingPieceUrl) return;
      var slug;
      try { slug = decodeURIComponent(location.hash.slice(1)); } catch (error) { return; }
      if (!piecesByKey[slug]) return;
      clearingPieceUrl = true;
      setTimeout(function () { clearingPieceUrl = false; }, 0);
      if (history.state && history.state.mwPiece) history.back();
      else history.replaceState(null, "", location.pathname + location.search);
    }
    dialogEl.addEventListener("close", clearPieceUrl);
    dialogEl.addEventListener("cancel", clearPieceUrl);
    function syncDialogWithHash() {
      if (!openPieceFromHash() && dialogEl.open) dialogEl.close();
    }
    window.addEventListener("popstate", syncDialogWithHash);
    window.addEventListener("hashchange", syncDialogWithHash);
    dialogEl.addEventListener("click", function (event) {
      if (event.target !== dialogEl) return;
      var rect = dialogEl.getBoundingClientRect();
      if (event.clientX < rect.left || event.clientX > rect.right ||
          event.clientY < rect.top || event.clientY > rect.bottom) dialogEl.close();
    });
    var closeBtn = dialogEl.querySelector(".dialog-close");
    if (closeBtn) {
      closeBtn.addEventListener("click", function () {
        dialogEl.close();
      });
    }
  }

  /** Same ISO-8601 week id the generator computes server-side (WeekFields.ISO) - Thursday-anchored
   * so it agrees with Java's week-based year at year boundaries too, not just week number. */
  function isoWeekId(date) {
    var d = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
    var dayNum = (d.getUTCDay() + 6) % 7; // Mon=0..Sun=6
    d.setUTCDate(d.getUTCDate() - dayNum + 3); // nearest Thursday
    var firstThursday = new Date(Date.UTC(d.getUTCFullYear(), 0, 4));
    var firstThursdayDayNum = (firstThursday.getUTCDay() + 6) % 7;
    firstThursday.setUTCDate(firstThursday.getUTCDate() - firstThursdayDayNum + 3);
    var weekNum = 1 + Math.round((d - firstThursday) / (7 * 24 * 3600 * 1000));
    return d.getUTCFullYear() + "-W" + (weekNum < 10 ? "0" : "") + weekNum;
  }

  // The generator only ever produces a new piece once per ISO week, on whichever day the daily
  // 13:00 UTC scheduler first runs after Monday 00:00 UTC. This estimates that moment plus a
  // couple hours of slack for job runtime and scheduling jitter - deliberately erring late so
  // the countdown doesn't hit zero before the piece actually exists.
  function nextGenerationEstimate() {
    var now = new Date();
    var target = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), 15, 0, 0));
    var daysUntilMonday = (1 - target.getUTCDay() + 7) % 7;
    // If today is Monday and this week's pieces are already published (generation runs well
    // before the 15:00 UTC slack cutoff above), the next real generation is next Monday, not
    // "later today" - without this check the countdown claims pieces are imminent for hours
    // after they've already shipped.
    if (daysUntilMonday === 0 && latestVersion && latestVersion === isoWeekId(now)) {
      daysUntilMonday = 7;
    }
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
