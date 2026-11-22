import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The website's door into the system. It only TALKS to the existing classes
 * (Database, User, Organization) through their public methods - it never touches their fields.
 * Works with every kind of User through polymorphism (getRole, getDashboardName, canPostRequests).
 */
public class WebServer {

    private static final long MAX_PROOF_BYTES = 5L * 1024 * 1024;               // 5 MB, same as the form says
    private static final List<String> PROOF_TYPES = List.of("pdf", "jpg", "jpeg", "png");
    private static final List<String> ORG_TYPES = List.of("LGU", "NGO", "Barangay", "Company");
    private static final List<String> DONOR_TYPES = List.of("Individual", "Group");

    private final Database db;
    private final Path root = Path.of("").toAbsolutePath();      // run the program from the DamayanSystem folder
    private final Map<String, String> sessions = new HashMap<>(); // cookie token -> user id
    private final SecureRandom random = new SecureRandom();

    private WebServer(Database db) { this.db = db; }

    public static void start(Database db, int port) {
        try {
            new WebServer(db).run(port);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the website: " + e.getMessage());
        }
    }

    private void run(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::serveFile);
        server.createContext("/api/login", this::login);
        server.createContext("/api/signup-donor", this::signupDonor);
        server.createContext("/api/signup-org", this::signupOrganization);
        server.createContext("/api/admin/summary", this::adminSummary);
        server.createContext("/api/admin/organizations", this::adminOrganizations);
        server.createContext("/api/admin/accounts", this::adminAccounts);
        server.createContext("/api/admin/review", this::adminReview);
        server.createContext("/api/admin/suspend", this::adminSuspend);
        server.createContext("/api/admin/proof", this::adminProof);
        server.createContext("/api/my-proof", this::myProof);
        server.createContext("/api/stats", this::stats);
        server.createContext("/api/orgs", this::orgList);
        server.createContext("/api/requests", this::browse);
        server.createContext("/api/match", this::match);
        server.createContext("/api/my", this::mine);
        server.createContext("/api/request", this::requestDetail);
        server.createContext("/api/post", this::postRequest);
        server.createContext("/api/donate", this::donate);
        server.createContext("/api/deliver", this::deliver);
        server.createContext("/api/receive", this::receive);
        server.createContext("/api/problem", this::problem);
        server.createContext("/api/me", this::me);
        server.createContext("/api/logout", this::logout);
        server.start();
        System.out.println("Website running at http://localhost:" + port);
    }

    // ---------- API ----------

    private void login(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> form = form(ex);
        String wantedRole = form.getOrDefault("role", "");
        synchronized (db) {
            Optional<User> found = db.login(form.getOrDefault("email", ""), form.getOrDefault("password", ""));
            if (found.isEmpty()) { json(ex, 401, fail("Wrong email or password.")); return; }
            User user = found.get();
            if (user.isSuspended()) { json(ex, 403, fail("This account is suspended. Please contact the admin.")); return; }
            if (!wantedRole.isEmpty() && !user.getRole().equals(wantedRole)) {
                json(ex, 403, fail("This account is registered as " + user.getRole().toLowerCase()
                        + ". Please use the matching log in page."));
                return;
            }
            startSession(ex, user);
            json(ex, 200, describe(user));
        }
    }

    /** Donor sign up: same rules as the text menu (Donor.register + db.addUser), then log the donor straight in. */
    private void signupDonor(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> f = form(ex);
        synchronized (db) {
            try {
                checkPasswordsMatch(f);
                String type = pick(f.get("type"), DONOR_TYPES, "Individual");
                Donor donor = Donor.register(f.getOrDefault("name", ""), f.getOrDefault("email", ""),
                        f.getOrDefault("password", ""), type);
                db.addUser(donor);
                startSession(ex, donor);
                json(ex, 200, describe(donor));
            } catch (IllegalArgumentException | IllegalStateException e) {
                json(ex, 400, fail(e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                json(ex, 500, fail("Server error: " + e));
            }
        }
    }

    /** Organization sign up: saves the uploaded proof, creates a PENDING organization, then logs it in. */
    private void signupOrganization(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> f = form(ex);
        synchronized (db) {
            Path temp = null;
            try {
                checkPasswordsMatch(f);
                String type = pick(f.get("type"), ORG_TYPES, null);
                if (type == null) throw new IllegalArgumentException("Please choose an organization type.");

                String proofName = safeProofName(f.get("proofName"));
                String regNo = f.getOrDefault("regNo", "").trim();
                if (regNo.isEmpty()) regNo = "N/A";           // the sign up form no longer asks for it
                Organization org = Organization.register(f.getOrDefault("name", ""), f.getOrDefault("email", ""),
                        f.getOrDefault("password", ""), type, regNo, proofName);
                if (db.findByEmail(org.getEmail()).isPresent()) throw new IllegalArgumentException("That email is already registered.");

                byte[] bytes = decodeProof(f.get("proof"));
                temp = Files.createTempFile("damayan-proof", ".tmp");
                Files.write(temp, bytes);
                db.saveProof(temp, org);        // copies it into data/proofs
                db.addUser(org);                // saves the account (status PENDING)

                startSession(ex, org);
                json(ex, 200, describe(org));
            } catch (IllegalArgumentException | IllegalStateException e) {
                json(ex, 400, fail(e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                json(ex, 500, fail("Server error: " + e));
            } finally {
                if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }


    // ---------- Requests: post, browse, match, donate, deliver, confirm ----------
    // The request itself is kept by AidRequest / Database (data/requests.txt) exactly like the text menu.
    // The few extras the website shows (details text and the dates) live in data/request-extra.txt.

    private String prettyDate(String iso) {
        if (iso == null || iso.isBlank()) return "-";
        try { return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)); }
        catch (RuntimeException e) { return "-"; }
    }

    /** The request keeps its three step dates inside toRecord() (fields 12, 13, 14), so we read them from there. */
    private String stepDate(AidRequest r, int field) {
        String[] p = r.toRecord().split("\\|", -1);
        return field < p.length ? prettyDate(p[field]) : "-";
    }

    private String requestJson(AidRequest r) {
        return "{\"id\":" + q(r.getId())
                + ",\"item\":" + q(r.getItem())
                + ",\"quantity\":" + r.getQuantity()
                + ",\"unit\":" + q(r.getUnit())
                + ",\"category\":" + q(r.getCategory().getLabel())
                + ",\"orgId\":" + q(r.getOrgId())
                + ",\"orgName\":" + q(r.getOrgName())
                + ",\"status\":" + q(r.getStatus().name())
                + ",\"daysWaiting\":" + r.getDaysWaiting()
                + ",\"details\":" + q(r.getDetails())
                + ",\"donorName\":" + q(r.getDonorName())
                + ",\"posted\":" + q(prettyDate(r.getDatePosted().toString()))
                + ",\"donated\":" + q(stepDate(r, 12))
                + ",\"delivered\":" + q(stepDate(r, 13))
                + ",\"received\":" + q(stepDate(r, 14)) + "}";
    }

    private String listJson(List<AidRequest> list) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
        for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append(','); sb.append(requestJson(list.get(i))); }
        return sb.append("]}").toString();
    }

    private AidRequest.Category category(String label, boolean allowAny) {
        String text = label == null ? "" : label.trim().toLowerCase();
        if (text.isEmpty() || text.equals("all") || text.equals("category")) {
            if (allowAny) return null;
            throw new IllegalArgumentException("Please choose a category.");
        }
        for (AidRequest.Category c : AidRequest.Category.values()) if (c.getLabel().toLowerCase().equals(text)) return c;
        for (AidRequest.Category c : AidRequest.Category.values()) {
            String l = c.getLabel().toLowerCase();
            if (l.contains(text) || text.contains(l)) return c;
        }
        throw new IllegalArgumentException("Unknown category.");
    }

    /** The type (LGU, NGO, Barangay, Company) of the organization that posted a request. */
    private String orgTypeOf(AidRequest r) {
        return db.findOrganization(r.getOrgId()).map(Organization::getOrgType).orElse("");
    }

    private Optional<Donor> donorUser(HttpExchange ex) { return currentUser(ex).filter(u -> u instanceof Donor).map(u -> (Donor) u); }
    private Optional<Organization> orgUser(HttpExchange ex) { return currentUser(ex).filter(u -> u instanceof Organization).map(u -> (Organization) u); }

    private void problemJson(HttpExchange ex, Exception e) throws IOException {
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) { json(ex, 400, fail(e.getMessage())); return; }
        e.printStackTrace();
        json(ex, 500, fail("Server error: " + e));
    }

    /** Public: how many donations were confirmed (home page). */
    private void stats(HttpExchange ex) throws IOException {
        synchronized (db) { json(ex, 200, "{\"ok\":true,\"received\":" + db.completedCount() + "}"); }
    }

    /** Verified organizations, for the Organization dropdowns. */
    private void orgList(HttpExchange ex) throws IOException {
        synchronized (db) {
            if (currentUser(ex).isEmpty()) { json(ex, 403, fail("Please log in.")); return; }
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
            boolean first = true;
            for (User u : db.allUsers()) {
                if (!(u instanceof Organization o) || o.getStatus() != VerificationStatus.VERIFIED) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":").append(q(o.getId())).append(",\"name\":").append(q(o.getName())).append('}');
            }
            json(ex, 200, sb.append("]}").toString());
        }
    }

    /** Browse Requests: waiting requests, optionally by category and organization. */
    private void browse(HttpExchange ex) throws IOException {
        synchronized (db) {
            if (donorUser(ex).isEmpty()) { json(ex, 403, fail("Donors only.")); return; }
            try {
                AidRequest.Category cat = category(queryParam(ex, "category"), true);
                String orgType = queryParam(ex, "orgType");
                List<AidRequest> out = new ArrayList<>();
                for (AidRequest r : db.waitingRequests(cat)) if (orgType.isBlank() || orgTypeOf(r).equalsIgnoreCase(orgType)) out.add(r);
                out.sort((a, b) -> Long.compare(b.getDaysWaiting(), a.getDaysWaiting()));
                json(ex, 200, listJson(out));
            } catch (Exception e) { problemJson(ex, e); }
        }
    }

    /** Find a Match: the same two strategies as the text menu, one after the other. */
    private void match(HttpExchange ex) throws IOException {
        synchronized (db) {
            if (donorUser(ex).isEmpty()) { json(ex, 403, fail("Donors only.")); return; }
            try {
                AidRequest.Category cat = category(queryParam(ex, "category"), true);
                String orgType = queryParam(ex, "orgType");
                Set<String> seen = new HashSet<>();
                for (String id : queryParam(ex, "seen").split(",")) if (!id.isBlank()) seen.add(id.trim());
                List<AidRequest> left = new ArrayList<>();
                for (AidRequest r : db.waitingRequests(cat)) {
                    if (seen.contains(r.getId())) continue;
                    if (!orgType.isBlank() && !orgTypeOf(r).equalsIgnoreCase(orgType)) continue;
                    left.add(r);
                }
                if (left.isEmpty()) { json(ex, 200, "{\"ok\":true,\"none\":true}"); return; }
                MatchStrategy[] strategies = { new OldestFirstMatch(), new LargestQuantityMatch() };
                MatchStrategy strategy = strategies[seen.size() % strategies.length];
                AidRequest match = strategy.pick(left).get();
                json(ex, 200, "{\"ok\":true,\"none\":false,\"strategy\":" + q(strategy.name())
                        + ",\"item\":" + requestJson(match) + "}");
            } catch (Exception e) { problemJson(ex, e); }
        }
    }

    /** My Requests (organization) or My Donations (donor). */
    private void mine(HttpExchange ex) throws IOException {
        synchronized (db) {
            Optional<User> user = currentUser(ex);
            if (user.isEmpty()) { json(ex, 403, fail("Please log in.")); return; }
            if (user.get() instanceof Organization o) { json(ex, 200, listJson(newestFirst(db.requestsOf(o)))); return; }
            if (user.get() instanceof Donor d) { json(ex, 200, listJson(newestFirst(db.donationsOf(d)))); return; }
            json(ex, 403, fail("Not available."));
        }
    }

    private List<AidRequest> newestFirst(List<AidRequest> list) {
        List<AidRequest> copy = new ArrayList<>(list);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** One request, for the Request Details page. */
    private void requestDetail(HttpExchange ex) throws IOException {
        synchronized (db) {
            Optional<User> user = currentUser(ex);
            if (user.isEmpty()) { json(ex, 403, fail("Please log in.")); return; }
            Optional<AidRequest> found = db.findRequest(queryParam(ex, "id"));
            if (found.isEmpty()) { json(ex, 404, fail("No request with that ID.")); return; }
            AidRequest r = found.get();
            boolean ok = user.get().getRole().equals("ADMIN")
                    || r.getOrgId().equals(user.get().getId())
                    || (r.getDonorId() != null && r.getDonorId().equals(user.get().getId()))
                    || (user.get() instanceof Donor && r.getStatus() == AidRequest.Status.WAITING);
            if (!ok) { json(ex, 403, fail("You cannot view this request.")); return; }
            json(ex, 200, "{\"ok\":true,\"item\":" + requestJson(r) + "}");
        }
    }

    /** Organization posts a new request (only verified organizations can). */
    private void postRequest(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> f = form(ex);
        synchronized (db) {
            Optional<Organization> org = orgUser(ex);
            if (org.isEmpty()) { json(ex, 403, fail("Organizations only.")); return; }
            try {
                AidRequest.Category cat = category(f.get("category"), false);
                String item = f.getOrDefault("item", "").trim();
                String unit = f.getOrDefault("unit", "").trim();
                if (item.isEmpty()) throw new IllegalArgumentException("Please say what you need.");
                if (unit.isEmpty()) throw new IllegalArgumentException("Please add a unit, such as packs or pieces.");
                int qty;
                try { qty = Integer.parseInt(f.getOrDefault("quantity", "").trim()); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Quantity must be a whole number."); }
                if (qty < 1) throw new IllegalArgumentException("Quantity must be at least 1.");
                String details = f.getOrDefault("details", "").trim();
                AidRequest request = org.get().postRequest(cat, item, qty, unit, details);
                db.addRequest(request);
                json(ex, 200, "{\"ok\":true,\"item\":" + requestJson(request) + "}");
            } catch (Exception e) { problemJson(ex, e); }
        }
    }

    /** Donor picks a waiting request. */
    private void donate(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> f = form(ex);
        synchronized (db) {
            Optional<Donor> donor = donorUser(ex);
            if (donor.isEmpty()) { json(ex, 403, fail("Donors only.")); return; }
            try {
                AidRequest r = db.findRequest(f.getOrDefault("id", "")).orElseThrow(() -> new IllegalArgumentException("No request with that ID."));
                donor.get().donateTo(r);
                db.save();
                json(ex, 200, "{\"ok\":true,\"item\":" + requestJson(r) + "}");
            } catch (Exception e) { problemJson(ex, e); }
        }
    }

    /** Donor says the items were sent. */
    private void deliver(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> f = form(ex);
        synchronized (db) {
            Optional<Donor> donor = donorUser(ex);
            if (donor.isEmpty()) { json(ex, 403, fail("Donors only.")); return; }
            try {
                AidRequest r = db.findRequest(f.getOrDefault("id", "")).orElseThrow(() -> new IllegalArgumentException("No request with that ID."));
                if (r.getDonorId() == null || !r.getDonorId().equals(donor.get().getId())) throw new IllegalArgumentException("This is not your donation.");
                donor.get().markDelivered(r);
                db.save();
                json(ex, 200, "{\"ok\":true,\"item\":" + requestJson(r) + "}");
            } catch (Exception e) { problemJson(ex, e); }
        }
    }

    /** Organization confirms the items arrived. */
    private void receive(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> f = form(ex);
        synchronized (db) {
            Optional<Organization> org = orgUser(ex);
            if (org.isEmpty()) { json(ex, 403, fail("Organizations only.")); return; }
            try {
                AidRequest r = db.findRequest(f.getOrDefault("id", "")).orElseThrow(() -> new IllegalArgumentException("No request with that ID."));
                if (!r.getOrgId().equals(org.get().getId())) throw new IllegalArgumentException("This is not your request.");
                org.get().confirmReceived(r);
                db.save();
                json(ex, 200, "{\"ok\":true,\"item\":" + requestJson(r) + "}");
            } catch (Exception e) { problemJson(ex, e); }
        }
    }

    /** "Report a problem" about a request: goes to the admin's reports, like the text menu. */
    private void problem(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> f = form(ex);
        synchronized (db) {
            Optional<User> user = currentUser(ex);
            if (user.isEmpty()) { json(ex, 403, fail("Please log in.")); return; }
            try {
                String message = f.getOrDefault("message", "").trim();
                if (message.isEmpty()) throw new IllegalArgumentException("Please describe the problem.");
                String about = db.findRequest(f.getOrDefault("id", "")).map(r -> "Request " + r.getId() + " (" + r.getItem() + "): ").orElse("");
                db.addReport(Report.create(user.get(), about + message));
                json(ex, 200, "{\"ok\":true}");
            } catch (Exception e) { problemJson(ex, e); }
        }
    }

    private void me(HttpExchange ex) throws IOException {
        synchronized (db) {
            Optional<User> user = currentUser(ex);
            json(ex, 200, user.isPresent() ? describe(user.get()) : "{\"ok\":false}");
        }
    }

    private void logout(HttpExchange ex) throws IOException {
        synchronized (db) {
            String token = cookie(ex, "session");
            if (token != null) sessions.remove(token);
        }
        ex.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0");
        json(ex, 200, "{\"ok\":true}");
    }

    private Optional<User> currentUser(HttpExchange ex) {
        String token = cookie(ex, "session");
        if (token == null || !sessions.containsKey(token)) return Optional.empty();
        return db.findUser(sessions.get(token)).filter(u -> !u.isSuspended());
    }

    private void startSession(HttpExchange ex, User user) {
        String token = newToken();
        sessions.put(token, user.getId());
        ex.getResponseHeaders().add("Set-Cookie", "session=" + token + "; Path=/; HttpOnly; SameSite=Lax");
    }

    /** One JSON description for any kind of user (polymorphism does the work). */
    private String describe(User u) {
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        sb.append(",\"id\":").append(q(u.getId()));
        sb.append(",\"role\":").append(q(u.getRole()));
        sb.append(",\"name\":").append(q(u.getName()));
        sb.append(",\"dashboard\":").append(q(u.getDashboardName()));
        sb.append(",\"canPost\":").append(u.canPostRequests());
        if (u instanceof Organization o) {
            sb.append(",\"status\":").append(q(o.getStatus().name()));
            sb.append(",\"reason\":").append(q(o.getRejectionReason()));
            sb.append(",\"submitted\":").append(q(proofDate(o)));
        }
        return sb.append("}").toString();
    }


    // ---------- Admin API (every call checks that the logged-in user is the admin) ----------

    private Optional<User> admin(HttpExchange ex) {
        return currentUser(ex).filter(u -> u.getRole().equals("ADMIN"));
    }

    private void adminSummary(HttpExchange ex) throws IOException {
        synchronized (db) {
            if (admin(ex).isEmpty()) { json(ex, 403, fail("Admin only.")); return; }
            int waiting = 0, donated = 0, delivered = 0, received = 0;
            for (User u : db.allUsers()) {
                if (!(u instanceof Organization o)) continue;
                for (AidRequest r : db.requestsOf(o)) {
                    switch (r.getStatus()) {
                        case WAITING -> waiting++;
                        case DONATED -> donated++;
                        case DELIVERED -> delivered++;
                        case RECEIVED -> received++;
                    }
                }
            }
            json(ex, 200, "{\"ok\":true"
                    + ",\"pending\":" + db.organizations(VerificationStatus.PENDING).size()
                    + ",\"verified\":" + db.organizations(VerificationStatus.VERIFIED).size()
                    + ",\"rejected\":" + db.organizations(VerificationStatus.REJECTED).size()
                    + ",\"waiting\":" + waiting + ",\"donated\":" + donated
                    + ",\"delivered\":" + delivered + ",\"received\":" + received + "}");
        }
    }

    private void adminOrganizations(HttpExchange ex) throws IOException {
        synchronized (db) {
            if (admin(ex).isEmpty()) { json(ex, 403, fail("Admin only.")); return; }
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
            boolean first = true;
            for (User u : db.allUsers()) {
                if (!(u instanceof Organization o)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":").append(q(o.getId()))
                  .append(",\"name\":").append(q(o.getName()))
                  .append(",\"email\":").append(q(o.getEmail()))
                  .append(",\"orgType\":").append(q(o.getOrgType()))
                  .append(",\"regNo\":").append(q(o.getRegistrationNo()))
                  .append(",\"proof\":").append(q(o.getProofDocument()))
                  .append(",\"status\":").append(q(o.getStatus().name()))
                  .append(",\"reason\":").append(q(o.getRejectionReason()))
                  .append(",\"suspended\":").append(o.isSuspended())
                  .append(",\"date\":").append(q(proofDate(o))).append('}');
            }
            json(ex, 200, sb.append("]}").toString());
        }
    }

    private void adminAccounts(HttpExchange ex) throws IOException {
        synchronized (db) {
            if (admin(ex).isEmpty()) { json(ex, 403, fail("Admin only.")); return; }
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
            boolean first = true;
            for (User u : db.allUsers()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":").append(q(u.getId()))
                  .append(",\"role\":").append(q(u.getRole()))
                  .append(",\"name\":").append(q(u.getName()))
                  .append(",\"email\":").append(q(u.getEmail()))
                  .append(",\"suspended\":").append(u.isSuspended())
                  .append(",\"date\":").append(q(u instanceof Organization o ? proofDate(o) : "-")).append('}');
            }
            json(ex, 200, sb.append("]}").toString());
        }
    }

    /** Approve or reject a Pending organization (same rules as the text menu). */
    private void adminReview(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> f = form(ex);
        synchronized (db) {
            if (admin(ex).isEmpty()) { json(ex, 403, fail("Admin only.")); return; }
            Optional<Organization> found = db.findOrganization(f.getOrDefault("id", ""));
            if (found.isEmpty()) { json(ex, 404, fail("No organization with that ID.")); return; }
            Organization org = found.get();
            try {
                switch (f.getOrDefault("action", "")) {
                    case "approve" -> org.markVerified();
                    case "reject"  -> org.markRejected(f.getOrDefault("reason", ""));
                    default -> throw new IllegalArgumentException("Unknown action.");
                }
                db.save();
                json(ex, 200, "{\"ok\":true,\"status\":" + q(org.getStatus().name()) + "}");
            } catch (IllegalArgumentException | IllegalStateException e) {
                json(ex, 400, fail(e.getMessage()));
            }
        }
    }

    private void adminSuspend(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, fail("Use POST.")); return; }
        Map<String, String> f = form(ex);
        synchronized (db) {
            if (admin(ex).isEmpty()) { json(ex, 403, fail("Admin only.")); return; }
            Optional<User> found = db.findUser(f.getOrDefault("id", ""));
            if (found.isEmpty()) { json(ex, 404, fail("No account with that ID.")); return; }
            User user = found.get();
            if (user.getRole().equals("ADMIN")) { json(ex, 400, fail("Admin accounts cannot be suspended here.")); return; }
            switch (f.getOrDefault("action", "")) {
                case "suspend"   -> user.suspend();
                case "reinstate" -> user.reinstate();
                default -> { json(ex, 400, fail("Unknown action.")); return; }
            }
            db.save();
            json(ex, 200, "{\"ok\":true,\"suspended\":" + user.isSuspended() + "}");
        }
    }

    /** Sends the proof file an organization uploaded (admin only). */
    private void adminProof(HttpExchange ex) throws IOException {
        byte[] bytes;
        String type;
        synchronized (db) {
            if (admin(ex).isEmpty()) { send(ex, 403, "text/plain", "Admin only.".getBytes(StandardCharsets.UTF_8)); return; }
            Optional<Organization> org = db.findOrganization(queryParam(ex, "id"));
            Path file = org.map(db::proofPath).orElse(null);
            if (file == null || !Files.isRegularFile(file)) { send(ex, 404, "text/plain", "Proof not found.".getBytes(StandardCharsets.UTF_8)); return; }
            bytes = Files.readAllBytes(file);
            type = mime(file.toString().toLowerCase());
        }
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, type, bytes);
    }

    /** An organization can open its own submitted proof (View submitted proof). */
    private void myProof(HttpExchange ex) throws IOException {
        byte[] bytes;
        String type;
        synchronized (db) {
            Optional<Organization> org = orgUser(ex);
            if (org.isEmpty()) { send(ex, 403, "text/plain", "Organizations only.".getBytes(StandardCharsets.UTF_8)); return; }
            Path file = db.proofPath(org.get());
            if (file == null || !Files.isRegularFile(file)) { send(ex, 404, "text/plain", "Proof not found.".getBytes(StandardCharsets.UTF_8)); return; }
            bytes = Files.readAllBytes(file);
            type = mime(file.toString().toLowerCase());
        }
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, type, bytes);
    }

    /** The upload time of the proof file is the only date the system keeps for an organization. */
    private String proofDate(Organization o) {
        try {
            var time = Files.getLastModifiedTime(db.proofPath(o)).toInstant();
            return DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(time);
        } catch (Exception e) {
            return "-";
        }
    }

    private String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) return "";
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) return kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
        }
        return "";
    }

    // ---------- sign up checks ----------

    private void checkPasswordsMatch(Map<String, String> f) {
        if (!f.getOrDefault("password", "").equals(f.getOrDefault("confirm", ""))) {
            throw new IllegalArgumentException("The two passwords do not match.");
        }
    }

    /** Returns the matching allowed value (ignoring upper/lower case), or the fallback. */
    private String pick(String value, List<String> allowed, String fallback) {
        if (value != null) for (String a : allowed) if (a.equalsIgnoreCase(value.trim())) return a;
        return fallback;
    }

    /** Keeps only the file name, safe characters, and an allowed extension (pdf, jpg, jpeg, png). */
    private String safeProofName(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Please upload a proof document.");
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!PROOF_TYPES.contains(ext)) throw new IllegalArgumentException("The proof must be a JPG, PNG or PDF file.");
        if (name.length() > 80) name = name.substring(0, 60) + "." + ext;
        return name;
    }

    private byte[] decodeProof(String base64) {
        if (base64 == null || base64.isBlank()) throw new IllegalArgumentException("Please upload a proof document.");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The proof file could not be read. Please upload it again.");
        }
        if (bytes.length == 0) throw new IllegalArgumentException("The proof file is empty.");
        if (bytes.length > MAX_PROOF_BYTES) throw new IllegalArgumentException("The proof file is bigger than 5 MB.");
        return bytes;
    }

    // ---------- Website files (only /public and /images are shared) ----------

    private void serveFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            ex.getResponseHeaders().set("Location", "/public/index.html");
            ex.sendResponseHeaders(302, -1);
            ex.close();
            return;
        }
        Path file = root.resolve(path.substring(1)).normalize();
        boolean allowed = file.startsWith(root.resolve("public")) || file.startsWith(root.resolve("images"));
        if (!allowed || !Files.isRegularFile(file)) { send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8)); return; }
        // Tell the browser not to reuse an old copy, so edits to index.html / style.css show up right away
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, mime(file.toString().toLowerCase()), Files.readAllBytes(file));
    }

    private String mime(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css"))  return "text/css; charset=utf-8";
        if (name.endsWith(".js"))   return "text/javascript; charset=utf-8";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg"))  return "image/svg+xml";
        if (name.endsWith(".pdf"))  return "application/pdf";
        if (name.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    // ---------- small helpers ----------

    private void json(HttpExchange ex, int status, String body) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    private String fail(String message) { return "{\"ok\":false,\"error\":" + q(message) + "}"; }

    private String q(String s) {
        String t = s == null ? "" : s;
        return "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
    }

    private Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }

    private String cookie(HttpExchange ex, String name) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}