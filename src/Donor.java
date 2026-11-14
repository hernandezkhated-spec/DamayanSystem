/** INHERITANCE: a Donor IS a User. It is an Individual or a Group. */
public class Donor extends User {
 
    private final String donorType;   // "Individual" or "Group"
 
    public Donor(String id, String name, String email, String passwordHash, boolean suspended, String donorType) {
        super(id, name, email, passwordHash, suspended);
        this.donorType = donorType;
    }
 
    public static Donor register(String name, String email, String password, String donorType) {
        if (password == null || password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters.");
        String type = (donorType == null || donorType.isBlank()) ? "Individual" : clean(donorType);
        return new Donor(newId(), name, email, hash(email, password), false, type);
    }
 
    public String getDonorType() { return donorType; }
 
    public void donateTo(AidRequest request) {
        if (isSuspended()) throw new IllegalStateException("Your account is suspended.");
        request.markDonated(this);
    }
 
    public void markDelivered(AidRequest request) { request.markDelivered(this); }
 
    @Override public String getRole()          { return "DONOR"; }
    @Override public String getDashboardName() { return "Donor Dashboard"; }
    @Override public boolean canPostRequests() { return false; }
    @Override protected String extraFields()   { return donorType; }
}
 