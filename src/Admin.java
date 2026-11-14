public class Admin extends User {
 
    public Admin(String id, String name, String email, String passwordHash, boolean suspended) {
        super(id, name, email, passwordHash, suspended);
    }
 
    public static Admin create(String name, String email, String password) {
        return new Admin(newId(), name, email, hash(email, password), false);
    }
    
    public void verify(Organization org)                { org.markVerified(); }
    public void reject(Organization org, String reason) { org.markRejected(reason); }
    public void reinstate(User user)                    { user.reinstate(); }
    public void resolve(Report report)                  { report.resolve(); }
 
    public void suspend(User user) {
        if (user instanceof Admin) throw new IllegalArgumentException("An admin account cannot be suspended.");
        user.suspend();
    }
 
    @Override public String getRole()          { return "ADMIN"; }
    @Override public String getDashboardName() { return "Admin Dashboard"; }
    @Override public boolean canPostRequests() { return false; }
    @Override protected String extraFields()   { return ""; }
}