package art.render.site;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal static site server for the Render gallery, following the same plain-JDK HttpServer
 * pattern used across other sites in this workspace: no framework, no database. The gallery
 * itself is rendered client-side by assets/site.js, which fetches manifest.json directly from
 * the public GCS bucket named by GCS_BUCKET - this server only needs to inject that bucket name
 * into index.html and otherwise serve static bytes.
 */
public final class StaticSiteServer {

    private static final String PUBLIC_ROOT = "/public";
    private static final byte[] NOT_FOUND = "Not found\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] METHOD_NOT_ALLOWED = "Method not allowed\n".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("webp", "image/webp"),
            Map.entry("xml", "application/xml; charset=utf-8")
    );
    private static final Set<String> COMPRESSIBLE_EXTENSIONS =
            Set.of("html", "css", "js", "json", "svg", "txt", "xml");

    private StaticSiteServer() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/healthz", StaticSiteServer::health);
        server.createContext("/", StaticSiteServer::serveStatic);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("Render site listening on port %d%n", port);
    }

    private static void health(HttpExchange exchange) throws IOException {
        applySecurityHeaders(exchange.getResponseHeaders());
        byte[] body = "ok\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void serveStatic(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            send(exchange, 405, "text/plain; charset=utf-8", METHOD_NOT_ALLOWED);
            return;
        }

        URI requestUri = exchange.getRequestURI();
        String path = sanitizePath(requestUri.getPath());
        if (path == null) {
            send(exchange, 404, "text/plain; charset=utf-8", NOT_FOUND);
            return;
        }

        byte[] body;
        if ("/index.html".equals(path)) {
            body = renderIndex();
        } else if (isGalleryDataPath(path) && localGalleryDir() != null) {
            body = readLocalGalleryFile(path);
        } else {
            body = readResource(path);
        }
        if (body == null) {
            redirectToHome(exchange);
            return;
        }

        boolean compressed = isCompressible(path) && acceptsGzip(exchange.getRequestHeaders());
        if (compressed) {
            body = gzip(body);
        }

        Headers headers = exchange.getResponseHeaders();
        applySecurityHeaders(headers);
        headers.set("Content-Type", contentType(path));
        headers.set("Cache-Control", cacheControl(path, requestUri.getRawQuery()));
        headers.set("Vary", "Accept-Encoding");
        if (compressed) {
            headers.set("Content-Encoding", "gzip");
        }
        exchange.sendResponseHeaders(200, "HEAD".equals(method) ? -1 : body.length);
        if (!"HEAD".equals(method)) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } else {
            exchange.close();
        }
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream input = StaticSiteServer.class.getResourceAsStream(PUBLIC_ROOT + path)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    /**
     * index.html is the one file rendered dynamically: a data-gcs-bucket attribute on <body> is
     * stamped with the GCS_BUCKET env var so site.js knows which public bucket to read
     * manifest.json/images from, without requiring a source edit before every deploy. Deliberately
     * NOT an inline <script> - this server's own CSP (script-src 'self') would silently block an
     * inline script from ever running, leaving the gallery permanently unconfigured. When
     * LOCAL_GALLERY_DIR is set (local preview only, see readLocalGalleryFile), site.js is told to
     * fetch manifest.json/images from this same server instead of GCS.
     *
     * index.html also stamps an {{ASSET_VERSION}} query param onto the /assets/* references, set
     * to Cloud Run's own K_REVISION (unique per deploy). index.html itself is served no-cache, so
     * every visit picks up the current revision's asset URLs, which lets cacheControl() give
     * those assets a long, immutable cache instead of the 24h default - without that, a browser
     * that cached an old site.js/styles.css before a deploy would keep serving it for up to a day.
     */
    private static byte[] renderIndex() throws IOException {
        byte[] raw = readResource("/index.html");
        if (raw == null) {
            return null;
        }
        String bucket = localGalleryDir() != null ? "__local__" : System.getenv().getOrDefault("GCS_BUCKET", "");
        String assetVersion = System.getenv().getOrDefault("K_REVISION", String.valueOf(System.currentTimeMillis()));
        String rendered = new String(raw, StandardCharsets.UTF_8)
                .replace("{{GCS_BUCKET}}", bucket)
                .replace("{{ASSET_VERSION}}", assetVersion);
        return rendered.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Local dev/preview only: serves manifest.json and images/* straight from the directory
     * the generator's LOCAL_OUT mode wrote to, so `mvn package` + running the generator once
     * is enough to see a real gallery page without any GCS bucket.
     */
    private static boolean isGalleryDataPath(String path) {
        return "/manifest.json".equals(path) || path.startsWith("/images/");
    }

    private static String localGalleryDir() {
        String dir = System.getenv("LOCAL_GALLERY_DIR");
        return (dir == null || dir.isBlank()) ? null : dir;
    }

    private static byte[] readLocalGalleryFile(String path) throws IOException {
        Path file = Path.of(localGalleryDir(), path.substring(1));
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }

    private static void redirectToHome(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        applySecurityHeaders(headers);
        headers.set("Location", "/");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(301, -1);
        exchange.close();
    }

    private static String sanitizePath(String rawPath) {
        String path = rawPath == null || rawPath.isBlank() || "/".equals(rawPath) ? "/index.html" : rawPath;
        if (path.endsWith("/")) {
            path += "index.html";
        }
        if (path.contains("..") || path.contains("\\") || path.contains("%00")) {
            return null;
        }
        return path;
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        applySecurityHeaders(headers);
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void applySecurityHeaders(Headers headers) {
        headers.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: https://storage.googleapis.com; "
                + "script-src 'self'; connect-src 'self' https://storage.googleapis.com; "
                + "style-src 'self'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
        headers.set("Strict-Transport-Security", "max-age=" + Duration.ofDays(365).toSeconds() + "; includeSubDomains");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
    }

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static boolean isCompressible(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return COMPRESSIBLE_EXTENSIONS.contains(extension);
    }

    private static boolean acceptsGzip(Headers requestHeaders) {
        String acceptEncoding = requestHeaders.getFirst("Accept-Encoding");
        return acceptEncoding != null && acceptEncoding.toLowerCase(Locale.ROOT).contains("gzip");
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream)) {
            gzipStream.write(data);
        }
        return byteStream.toByteArray();
    }

    private static String cacheControl(String path, String rawQuery) {
        if ("/index.html".equals(path)) {
            return "no-cache";
        }
        if (path.startsWith("/assets/") && !path.endsWith(".html")) {
            boolean cacheBusted = rawQuery != null && !rawQuery.isBlank();
            return cacheBusted ? "public, max-age=31536000, immutable" : "public, max-age=86400";
        }
        return "no-cache";
    }
}
