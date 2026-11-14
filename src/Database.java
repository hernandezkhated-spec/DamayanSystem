import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Database {
 
    private final Path folder;
    private final Path usersFile;
    private final Path requestsFile;
    private final Path reportsFile;
 
    private final List<User> users = new ArrayList<>();
    private final List<AidRequest> requests = new ArrayList<>();
    private final List<Report> reports = new ArrayList<>();
 
    public Database(Path folder) {
        this.folder = folder;
        this.usersFile = folder.resolve("users.txt");
        this.requestsFile = folder.resolve("requests.txt");
        this.reportsFile = folder.resolve("reports.txt");
        try {
            Files.createDirectories(folder.resolve("proofs"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the data folder.", e);
        }
        load();
        seedAdmin();
    }
 
    public void addUser(User user) {
        if (findByEmail(user.getEmail()).isPresent()) throw new IllegalArgumentException("That email is already registered.");
        users.add(user);
        save();
    }
 
    public Optional<User> findByEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase();
        return users.stream().filter(u -> u.getEmail().equals(e)).findFirst();
    }
 
    public Optional<User> findUser(String id) {
        return users.stream().filter(u -> u.getId().equals(id)).findFirst();
    }
 
    public Optional<Organization> findOrganization(String id) {
        return findUser(id).filter(u -> u instanceof Organization).map(u -> (Organization) u);
    }
 
    /** Returns the user only if the email and password are right (a suspended user is still returned). */
    public Optional<User> login(String email, String password) {
        return findByEmail(email).filter(u -> u.checkPassword(password));
    }
 
    public List<User> allUsers() { return new ArrayList<>(users); }
 
    public List<Organization> organizations(VerificationStatus status) {
        List<Organization> result = new ArrayList<>();
        for (User u : users) {
            if (u instanceof Organization o && o.getStatus() == status) result.add(o);
        }
        return result;
    }
 
    public Path proofPath(Organization org) {
        return folder.resolve("proofs").resolve(org.getId() + "-" + org.getProofDocument());
    }
 
    /** "Upload": copies the chosen file into the data/proofs folder. */
    public void saveProof(Path source, Organization org) {
        try {
            Files.copy(source, proofPath(org), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not upload the document: " + e.getMessage());
        }
    }
 
 
    public void addRequest(AidRequest request) {
        requests.add(request);
        save();
    }
 
    public Optional<AidRequest> findRequest(String id) {
        return requests.stream().filter(r -> r.getId().equals(id)).findFirst();
    }
 
    /** Requests still waiting for a donor. Category null means "any category". */
    public List<AidRequest> waitingRequests(AidRequest.Category category) {
        List<AidRequest> result = new ArrayList<>();
        for (AidRequest r : requests) {
            if (r.getStatus() == AidRequest.Status.WAITING && (category == null || r.getCategory() == category)) result.add(r);
        }
        return result;
    }
 
    public List<AidRequest> requestsOf(Organization org) {
        List<AidRequest> result = new ArrayList<>();
        for (AidRequest r : requests) if (r.getOrgId().equals(org.getId())) result.add(r);
        return result;
    }
 
    public List<AidRequest> donationsOf(Donor donor) {
        List<AidRequest> result = new ArrayList<>();
        for (AidRequest r : requests) if (r.getDonorId().equals(donor.getId())) result.add(r);
        return result;
    }
 
    public long completedCount() {
        return requests.stream().filter(r -> r.getStatus() == AidRequest.Status.RECEIVED).count();
    }
 
    public void addReport(Report report) {
        reports.add(report);
        save();
    }
 
    public Optional<Report> findReport(String id) {
        return reports.stream().filter(r -> r.getId().equals(id)).findFirst();
    }
 
    public List<Report> reports(Report.Status status) {
        List<Report> result = new ArrayList<>();
        for (Report r : reports) if (r.getStatus() == status) result.add(r);
        return result;
    }
 
    public void save() {
        write(usersFile, new ArrayList<Storable>(users));
        write(requestsFile, new ArrayList<Storable>(requests));
        write(reportsFile, new ArrayList<Storable>(reports));
    }
 
    private void write(Path file, List<Storable> items) {
        List<String> lines = new ArrayList<>();
        for (Storable s : items) lines.add(s.toRecord());
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + file.getFileName(), e);
        }
    }
 
    private void load() {
        for (String line : read(usersFile))    users.add(userFromRecord(line));
        for (String line : read(requestsFile)) requests.add(AidRequest.fromRecord(line));
        for (String line : read(reportsFile))  reports.add(Report.fromRecord(line));
    }
 
    private List<String> read(Path file) {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!line.isBlank()) lines.add(line);
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file.getFileName(), e);
        }
    }

    private User userFromRecord(String line) {
        String[] p = line.split("\\|", -1);
        boolean suspended = Boolean.parseBoolean(p[5]);
        return switch (p[0]) {
            case "ORGANIZATION" -> new Organization(p[1], p[2], p[3], p[4], suspended,
                    p[6], p[7], p[8], VerificationStatus.valueOf(p[9]), p[10]);
            case "DONOR" -> new Donor(p[1], p[2], p[3], p[4], suspended, p[6]);
            case "ADMIN" -> new Admin(p[1], p[2], p[3], p[4], suspended);
            default -> throw new IllegalStateException("Unknown user role: " + p[0]);
        };
    }

    private void seedAdmin() {
        boolean hasAdmin = users.stream().anyMatch(u -> u instanceof Admin);
        if (!hasAdmin) {
            users.add(Admin.create("Damayan Admin", "admin@damayan.local", "admin123")); // change this password!
            save();
        }
    }
}