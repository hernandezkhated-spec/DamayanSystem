import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static final Scanner in = new Scanner(System.in);
    private static final Database db = new Database(Path.of("data"));

    public static void main(String[] args) {
     WebServer.start(db, 8080);
        System.out.println("=== DAMAYAN: Community Aid and Donation Management System ===");
        while (true) {
            System.out.println("\n1. Sign up as an Organization\n2. Sign up as a Donor\n3. Log in\n0. Exit");
            try {
                switch (ask("Choose")) {
                    case "1" -> signUpOrganization();
                    case "2" -> signUpDonor();
                    case "3" -> logIn();
                    case "0" -> { System.out.println("Goodbye!"); return; }
                    default  -> System.out.println("Please choose a number from the menu.");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println("! " + e.getMessage());
            }
        }
    }


    private static void signUpOrganization() {
        String name = ask("Organization name");
        String email = ask("Email");
        String password = ask("Password (6+ characters)");
        String type = ask("Type (LGU / NGO / Barangay / Company)");
        String regNo = ask("Registration number");
        Path proof = askFile("Path of your proof document (PDF or image)");

        Organization org = Organization.register(name, email, password, type, regNo, proof.getFileName().toString());
        db.saveProof(proof, org);
        db.addUser(org);
        System.out.println("Account created. Status: PENDING - please wait for the admin to verify you.");
    }

    private static void signUpDonor() {
        String name = ask("Name");
        String email = ask("Email");
        String password = ask("Password (6+ characters)");
        String type = ask("Donor type (Individual / Group)");
        db.addUser(Donor.register(name, email, password, type));
        System.out.println("Account created. You can log in now.");
    }

    private static void logIn() {
        User user = db.login(ask("Email"), ask("Password"))
                .orElseThrow(() -> new IllegalArgumentException("Wrong email or password."));
        if (user.isSuspended()) throw new IllegalStateException("This account is suspended. Please contact the admin.");

        System.out.println("\n" + user.greeting() + "  ->  " + user.getDashboardName());
        // Each kind of user gets its own menu
        if (user instanceof Organization o) organizationMenu(o);
        else if (user instanceof Donor d) donorMenu(d);
        else if (user instanceof Admin a) adminMenu(a);
    }

    private static void organizationMenu(Organization o) {
        while (true) {
            System.out.println("\n--- Organization Dashboard ---  Status: " + o.getStatus()
                    + (o.getStatus() == VerificationStatus.REJECTED ? " (" + o.getRejectionReason() + ")" : ""));
            System.out.println("1. Post a request\n2. My requests\n3. Confirm a delivery was received\n4. Resubmit proof document\n5. Report a problem\n0. Log out");
            try {
                switch (ask("Choose")) {
                    case "1" -> postRequest(o);
                    case "2" -> db.requestsOf(o).forEach(r -> System.out.println(describe(r)));
                    case "3" -> confirmReceived(o);
                    case "4" -> resubmit(o);
                    case "5" -> reportProblem(o);
                    case "0" -> { return; }
                    default  -> System.out.println("Please choose a number from the menu.");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println("! " + e.getMessage());
            }
        }
    }

    private static void postRequest(Organization o) {
        AidRequest.Category category = askCategory(false);
        String item = ask("What do you need? (e.g. Rice)");
        int quantity = askInt("Quantity");
        String unit = ask("Unit (e.g. sacks, pieces)");
        String details = ask("Details (optional)");
        db.addRequest(o.postRequest(category, item, quantity, unit, details));
        System.out.println("Request posted. It is now WAITING for a donor.");
    }

    private static void confirmReceived(Organization o) {
        for (AidRequest r : db.requestsOf(o)) if (r.getStatus() == AidRequest.Status.DELIVERED) System.out.println(describe(r));
        AidRequest r = askRequest("ID of the request you received");
        o.confirmReceived(r);
        db.save();
        System.out.println("Thank you! The request is now RECEIVED (completed).");
    }

    private static void resubmit(Organization o) {
        Path file = askFile("Path of the new proof document");
        o.resubmit(file.getFileName().toString());
        db.saveProof(file, o);
        db.save();
        System.out.println("Resubmitted. Status: PENDING - wait for the admin.");
    }

    private static void donorMenu(Donor d) {
        while (true) {
            System.out.println("\n--- Donor Dashboard ---");
            System.out.println("1. Find a Match (the system picks)\n2. Pick a request myself\n3. My donations\n4. Mark a donation as delivered\n5. Report a problem\n0. Log out");
            try {
                switch (ask("Choose")) {
                    case "1" -> findMatch(d);
                    case "2" -> pickMyself(d);
                    case "3" -> db.donationsOf(d).forEach(r -> System.out.println(describe(r)));
                    case "4" -> markDelivered(d);
                    case "5" -> reportProblem(d);
                    case "0" -> { return; }
                    default  -> System.out.println("Please choose a number from the menu.");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println("! " + e.getMessage());
            }
        }
    }

    private static void findMatch(Donor d) {
        AidRequest.Category category = askCategory(true);
        List<AidRequest> left = new ArrayList<>(db.waitingRequests(category));
        MatchStrategy[] strategies = { new OldestFirstMatch(), new LargestQuantityMatch() };
        int turn = 0;
        while (!left.isEmpty()) {
            MatchStrategy strategy = strategies[turn % strategies.length];
            AidRequest match = strategy.pick(left).get();
            left.remove(match);
            turn++;
            System.out.println("\nMatch (" + strategy.name() + "): " + describe(match));
            String choice = ask("Donate to this? y = yes, a = show another, n = cancel");
            if (choice.equalsIgnoreCase("y")) { donate(d, match); return; }
            if (choice.equalsIgnoreCase("n")) return;
        }
        System.out.println("No more waiting requests.");
    }

    private static void pickMyself(Donor d) {
        List<AidRequest> waiting = db.waitingRequests(askCategory(true));
        if (waiting.isEmpty()) { System.out.println("No waiting requests."); return; }
        waiting.forEach(r -> System.out.println(describe(r)));
        String id = ask("ID of the request to donate to (Enter = back)");
        if (id.isEmpty()) return;
        donate(d, db.findRequest(id).orElseThrow(() -> new IllegalArgumentException("No request with that ID.")));
    }

    private static void donate(Donor d, AidRequest r) {
        d.donateTo(r);
        db.save();
        System.out.println("Thank you! You are donating: " + r.getItem() + " to " + r.getOrgName() + ".");
    }

    private static void markDelivered(Donor d) {
        for (AidRequest r : db.donationsOf(d)) if (r.getStatus() == AidRequest.Status.DONATED) System.out.println(describe(r));
        AidRequest r = askRequest("ID of the donation you delivered");
        d.markDelivered(r);
        db.save();
        System.out.println("Marked as DELIVERED. The organization will confirm it.");
    }

    private static void adminMenu(Admin a) {
        while (true) {
            System.out.println("\n--- Admin Dashboard ---");
            System.out.println("Pending: " + db.organizations(VerificationStatus.PENDING).size()
                    + " | Verified: " + db.organizations(VerificationStatus.VERIFIED).size()
                    + " | Waiting requests: " + db.waitingRequests(null).size()
                    + " | Completed: " + db.completedCount()
                    + " | Open reports: " + db.reports(Report.Status.OPEN).size());
            System.out.println("1. Review organizations (verify / reject)\n2. Accounts (suspend / reinstate)\n3. Reports\n0. Log out");
            try {
                switch (ask("Choose")) {
                    case "1" -> reviewOrganizations(a);
                    case "2" -> manageAccounts(a);
                    case "3" -> handleReports(a);
                    case "0" -> { return; }
                    default  -> System.out.println("Please choose a number from the menu.");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println("! " + e.getMessage());
            }
        }
    }

    private static void reviewOrganizations(Admin a) {
        List<Organization> pending = db.organizations(VerificationStatus.PENDING);
        if (pending.isEmpty()) { System.out.println("No organizations are waiting."); return; }
        for (Organization o : pending) {
            System.out.println("[" + o.getId() + "] " + o.getName() + " | " + o.getOrgType()
                    + " | Reg no: " + o.getRegistrationNo() + " | Proof: " + db.proofPath(o));
        }
        String id = ask("ID to review (Enter = back)");
        if (id.isEmpty()) return;
        Organization org = db.findOrganization(id).orElseThrow(() -> new IllegalArgumentException("No organization with that ID."));
        String choice = ask("v = verify, r = reject");
        if (choice.equalsIgnoreCase("v")) {
            a.verify(org);
            System.out.println(org.getName() + " is now VERIFIED.");
        } else if (choice.equalsIgnoreCase("r")) {
            a.reject(org, ask("Reason for rejecting"));
            System.out.println(org.getName() + " was REJECTED.");
        } else {
            System.out.println("Cancelled.");
            return;
        }
        db.save();
    }

    private static void manageAccounts(Admin a) {
        for (User u : db.allUsers()) {
            System.out.println("[" + u.getId() + "] " + u.getRole() + " | " + u.getName() + " | " + u.getEmail()
                    + (u.isSuspended() ? " | SUSPENDED" : ""));
        }
        String id = ask("ID to suspend or reinstate (Enter = back)");
        if (id.isEmpty()) return;
        User user = db.findUser(id).orElseThrow(() -> new IllegalArgumentException("No account with that ID."));
        if (user.isSuspended()) {
            a.reinstate(user);
            System.out.println(user.getName() + " was reinstated.");
        } else {
            a.suspend(user);
            System.out.println(user.getName() + " was suspended.");
        }
        db.save();
    }

    private static void handleReports(Admin a) {
        List<Report> open = db.reports(Report.Status.OPEN);
        if (open.isEmpty()) { System.out.println("No open reports."); return; }
        for (Report r : open) {
            System.out.println("[" + r.getId() + "] " + r.getDatePosted() + " from " + r.getReporterName() + ": " + r.getMessage());
        }
        String id = ask("ID of the report to mark resolved (Enter = back)");
        if (id.isEmpty()) return;
        a.resolve(db.findReport(id).orElseThrow(() -> new IllegalArgumentException("No report with that ID.")));
        db.save();
        System.out.println("Report resolved.");
    }

    private static void reportProblem(User u) {
        db.addReport(Report.create(u, ask("Describe the problem")));
        System.out.println("Thank you. Your report was sent to the admin.");
    }

    private static String describe(AidRequest r) {
        return "[" + r.getId() + "] " + r.getItem() + " - " + r.getQuantity() + " " + r.getUnit()
                + " (" + r.getCategory().getLabel() + ") from " + r.getOrgName()
                + " | " + r.getStatus() + ", waiting " + r.getDaysWaiting() + " day(s)";
    }

    private static String ask(String label) {
        System.out.print(label + ": ");
        if (!in.hasNextLine()) System.exit(0);
        return in.nextLine().trim();
    }

    private static int askInt(String label) {
        try {
            return Integer.parseInt(ask(label));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Please type a number.");
        }
    }

    private static Path askFile(String label) {
        Path file = Path.of(ask(label).replace("\"", ""));
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("That file was not found.");
        return file;
    }

    private static AidRequest askRequest(String label) {
        return db.findRequest(ask(label)).orElseThrow(() -> new IllegalArgumentException("No request with that ID."));
    }

    private static AidRequest.Category askCategory(boolean allowAny) {
        AidRequest.Category[] all = AidRequest.Category.values();
        for (int i = 0; i < all.length; i++) System.out.println("  " + (i + 1) + ". " + all[i].getLabel());
        String text = ask(allowAny ? "Category number (Enter = any)" : "Category number");
        if (allowAny && text.isEmpty()) return null;
        try {
            return all[Integer.parseInt(text) - 1];
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Please choose a number from the list.");
        }
    }
}