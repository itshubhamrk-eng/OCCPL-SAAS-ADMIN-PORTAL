package com.occpl.platform.identity;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Drives Keycloak's real browser login pages (authorization code + PKCE) over HTTP, including
 * authenticator-app enrolment and OTP entry, so MFA can be verified end to end without a browser.
 * Parses the pinned Keycloak version's login theme; update the selectors when upgrading Keycloak.
 * Public because e2e-tests reuse it through the core-platform test jar.
 */
public final class KeycloakLoginFlow {

    static final String REDIRECT_URI = "http://localhost:4200/callback";
    private static final Pattern FORM = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private final String realmUrl;
    private final String clientId;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    /** Single-host cookie jar (java.net.CookieManager drops Keycloak's cookies on http://localhost). */
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String otpSecret;

    public KeycloakLoginFlow(String realmUrl, String clientId) {
        this.realmUrl = realmUrl;
        this.clientId = clientId;
    }

    public String otpSecret() {
        return otpSecret;
    }

    /** Logs in; enrols TOTP if Keycloak asks for it; answers the OTP prompt with {@code otpSecret}. */
    public String login(String username, String password, String knownOtpSecret, long otpStepOffset) throws Exception {
        String verifier = randomUrlSafe(48);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authUrl = realmUrl + "/protocol/openid-connect/auth?" + form(Map.of(
                "client_id", clientId, "response_type", "code", "scope", "openid", "redirect_uri", REDIRECT_URI,
                "state", randomUrlSafe(16), "code_challenge", challenge, "code_challenge_method", "S256"));

        Page page = get(authUrl);
        page = post(formAction(page.body, "kc-form-login"), Map.of("username", username, "password", password));
        for (int step = 0; page.code == null && step < 5; step++) {
            if (page.body.contains("kc-totp-settings-form")) {
                otpSecret = inputValue(page.body, "totpSecret");
                page = post(formAction(page.body, "kc-totp-settings-form"), Map.of("totp",
                        totp(otpSecret, otpStepOffset), "totpSecret", otpSecret, "userLabel", "integration-test"));
            } else if (page.body.contains("kc-otp-login-form")) {
                page = post(formAction(page.body, "kc-otp-login-form"),
                        Map.of("otp", totp(knownOtpSecret, otpStepOffset)));
            } else {
                java.nio.file.Files.writeString(java.nio.file.Path.of("target/keycloak-unexpected-page.html"), page.body);
                throw new IllegalStateException("Unexpected Keycloak page: " + snippet(page.body));
            }
        }
        if (page.code == null) {
            throw new IllegalStateException("Login did not complete");
        }
        HttpResponse<String> token = http.send(HttpRequest.newBuilder(
                        URI.create(realmUrl + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "authorization_code",
                        "client_id", clientId, "code", page.code, "redirect_uri", REDIRECT_URI,
                        "code_verifier", verifier)))).build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(token.body());
        if (!m.find()) {
            throw new IllegalStateException("No access token: HTTP " + token.statusCode());
        }
        return m.group(1);
    }

    /**
     * Opens a Keycloak "required actions" email link, sets {@code newPassword} and returns the URL the
     * user is finally sent to (the redirect_uri given when the email was requested).
     */
    String completeActionsEmail(String link, String newPassword) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(link)).GET().build());
        for (int step = 0; step < 8; step++) {
            if (response.statusCode() / 100 == 3) {
                URI target = response.uri().resolve(response.headers().firstValue("Location").orElseThrow());
                if (target.toString().startsWith("http://localhost:4200")) {
                    return target.toString();
                }
                response = send(withCookies(HttpRequest.newBuilder(target)).GET().build());
                continue;
            }
            String html = response.body();
            if (html.contains("kc-passwd-update-form")) {
                response = send(withCookies(HttpRequest.newBuilder(URI.create(formAction(html, "kc-passwd-update-form"))))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("password-new", newPassword,
                                "password-confirm", newPassword)))).build());
                continue;
            }
            Matcher proceed = Pattern.compile("href=\"([^\"]+)\"[^>]*>[^<]{0,20}?(Click here to proceed|Back to Application)",
                    Pattern.CASE_INSENSITIVE).matcher(html);
            if (proceed.find()) {
                URI target = response.uri().resolve(unescape(proceed.group(1)));
                if (target.toString().startsWith("http://localhost:4200")) {
                    return target.toString();
                }
                response = send(withCookies(HttpRequest.newBuilder(target)).GET().build());
                continue;
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/keycloak-unexpected-page.html"), html);
            throw new IllegalStateException("Unexpected Keycloak page: " + snippet(html));
        }
        throw new IllegalStateException("Required actions did not complete");
    }

    /** Blocks until the next 30-second OTP window, because a code cannot be reused within its window. */
    public static void awaitNextOtpWindow() throws InterruptedException {
        long now = System.currentTimeMillis();
        Thread.sleep(30_000 - now % 30_000 + 500);
    }

    /** RFC 6238 TOTP with Keycloak's realm policy (HmacSHA256, 6 digits, 30 s) over the raw secret. */
    static String totp(String secret, long stepOffset) throws Exception {
        long counter = System.currentTimeMillis() / 1000 / 30 + stepOffset;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
        int offset = hash[hash.length - 1] & 0xF;
        int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
        return String.format("%06d", binary % 1_000_000);
    }

    private record Page(String body, String code) {
    }

    private Page get(String url) throws Exception {
        return follow(send(withCookies(HttpRequest.newBuilder(URI.create(url))).GET().build()));
    }

    private Page post(String url, Map<String, String> fields) throws Exception {
        return follow(send(withCookies(HttpRequest.newBuilder(URI.create(url)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build()));
    }

    private HttpRequest.Builder withCookies(HttpRequest.Builder builder) {
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        for (String header : response.headers().allValues("Set-Cookie")) {
            String[] nameValue = header.split(";", 2)[0].split("=", 2);
            if (nameValue.length == 2) {
                if (nameValue[1].isEmpty() || header.toLowerCase().contains("max-age=0")) {
                    cookies.remove(nameValue[0].strip());
                } else {
                    cookies.put(nameValue[0].strip(), nameValue[1]);
                }
            }
        }
        return response;
    }

    private Page follow(HttpResponse<String> response) throws Exception {
        while (response.statusCode() / 100 == 3) {
            String location = response.headers().firstValue("Location").orElseThrow();
            URI target = response.uri().resolve(location);
            if (target.toString().startsWith(REDIRECT_URI)) {
                return new Page("", queryParam(target, "code"));
            }
            response = send(withCookies(HttpRequest.newBuilder(target)).GET().build());
        }
        return new Page(response.body(), null);
    }

    private static String formAction(String html, String formId) {
        Matcher m = FORM.matcher(html);
        while (m.find()) {
            String tag = m.group();
            if (tag.contains("id=\"" + formId + "\"")) {
                return unescape(attribute(tag, "action"));
            }
        }
        throw new IllegalStateException("Form " + formId + " not found: " + snippet(html));
    }

    private static String inputValue(String html, String name) {
        Matcher m = INPUT.matcher(html);
        while (m.find()) {
            String tag = m.group();
            if (tag.contains("name=\"" + name + "\"")) {
                return unescape(attribute(tag, "value"));
            }
        }
        throw new IllegalStateException("Input " + name + " not found");
    }

    private static String attribute(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        if (!m.find()) {
            throw new IllegalStateException("Attribute " + name + " missing in " + tag);
        }
        return m.group(1);
    }

    private static String queryParam(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("No " + name + " in redirect");
    }

    private static String form(Map<String, String> fields) {
        return new LinkedHashMap<>(fields).entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String unescape(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#61;", "=");
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Page title, form ids and alert text, for failure messages. */
    private static String snippet(String html) {
        StringBuilder out = new StringBuilder();
        Matcher title = Pattern.compile("<title>([^<]*)</title>").matcher(html);
        if (title.find()) {
            out.append("title=").append(title.group(1).strip());
        }
        Matcher ids = Pattern.compile("<(?:form|h1)\\b[^>]*id=\"([^\"]+)\"").matcher(html);
        while (ids.find()) {
            out.append(" id=").append(ids.group(1));
        }
        Matcher alert = Pattern.compile("class=\"[^\"]*(?:alert|kc-feedback-text)[^\"]*\"[^>]*>\\s*([^<]{1,200})").matcher(html);
        while (alert.find()) {
            out.append(" alert=").append(alert.group(1).strip());
        }
        return out.toString();
    }
}
