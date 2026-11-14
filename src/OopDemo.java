import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;


public class OopDemo {

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("damayan-demo");
        Database db = new Database(temp);
        Admin admin = (Admin) db.findByEmail("admin@damayan.local").get();

        Organization org = Organization.register("Barangay San Roque", "bsr@mail.com", "secret123", "Barangay", "REG-001", "permit.pdf");
        Donor donor = Donor.register("Juan Dela Cruz", "juan@mail.com", "secret123", "Individual");
        db.addUser(org);
        db.addUser(donor);

        System.out.println("=== 1. ENCAPSULATION (private data + rules) ===");
        System.out.println("Correct password accepted? " + org.checkPassword("secret123"));
        System.out.println("Wrong password accepted?   " + org.checkPassword("wrong"));
        try { org.setName("   "); } catch (IllegalArgumentException e) { System.out.println("Blocked: " + e.getMessage()); }
        try { org.postRequest(AidRequest.Category.FOOD, "Rice", 10, "sacks", ""); }
        catch (IllegalStateException e) { System.out.println("Blocked: " + e.getMessage()); }

        System.out.println("\n=== 2. INHERITANCE (all three extend User) ===");
        for (User u : List.of(org, donor, admin)) {
            System.out.println(u.getClass().getSimpleName() + " is a User? " + (u instanceof User) + "  id=" + u.getId());
        }

        System.out.println("\n=== 3. POLYMORPHISM (same call, different result) ===");
        for (User u : List.of(org, donor, admin)) {
            System.out.println(u.greeting() + " -> " + u.getDashboardName() + " | can post requests? " + u.canPostRequests());
        }

        System.out.println("\n=== 4. ABSTRACTION (abstract class + interfaces) ===");
        System.out.println("User is abstract, so \"new User()\" is not allowed. Storable and MatchStrategy are interfaces.");

        System.out.println("\n=== Verification flow ===");
        admin.reject(org, "Document is blurry");
        System.out.println("After reject:   " + org.getStatus() + " (" + org.getRejectionReason() + ")");
        org.resubmit("permit-clear.pdf");
        System.out.println("After resubmit: " + org.getStatus());
        admin.verify(org);
        System.out.println("After verify:   " + org.getStatus() + " | can post? " + org.canPostRequests());

        AidRequest rice = org.postRequest(AidRequest.Category.FOOD, "Rice", 10, "sacks", "For 40 families");
        AidRequest cans = org.postRequest(AidRequest.Category.FOOD, "Canned goods", 200, "cans", "Relief packs");
        db.addRequest(rice);
        db.addRequest(cans);
        System.out.println("\n=== Find a Match (two strategies) ===");
        for (MatchStrategy s : List.of(new OldestFirstMatch(), new LargestQuantityMatch())) {
            Optional<AidRequest> pick = s.pick(db.waitingRequests(null));
            System.out.println(s.name() + " picks: " + pick.get().getItem());
        }

        System.out.println("\n=== Request life cycle ===");
        donor.donateTo(rice);
        System.out.println("After donate:   " + rice.getStatus());
        donor.markDelivered(rice);
        System.out.println("After delivery: " + rice.getStatus());
        org.confirmReceived(rice);
        System.out.println("After confirm:  " + rice.getStatus());
        try { donor.donateTo(rice); } catch (IllegalStateException e) { System.out.println("Blocked: " + e.getMessage()); }

        System.out.println("\n=== Suspend and Report a Problem ===");
        admin.suspend(org);
        System.out.println("Suspended org can post? " + org.canPostRequests());
        try { admin.suspend(admin); } catch (IllegalArgumentException e) { System.out.println("Blocked: " + e.getMessage()); }
        admin.reinstate(org);
        System.out.println("After reinstate, can post? " + org.canPostRequests());
        Report report = Report.create(donor, "The Find a Match page is slow.");
        db.addReport(report);
        System.out.println("Open reports: " + db.reports(Report.Status.OPEN).size());
        admin.resolve(report);
        System.out.println("Open reports after resolve: " + db.reports(Report.Status.OPEN).size());

        db.save();
        Database reloaded = new Database(temp);
        System.out.println("\n=== Save and load ===");
        System.out.println("After reloading from the files, organizations found: "
                + reloaded.organizations(VerificationStatus.VERIFIED).size()
                + ", completed requests: " + reloaded.completedCount());
    }
}