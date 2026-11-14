
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

public abstract class User implements Storable {
 
    private final String id;
    private String name;
    private final String email;
    private final String passwordHash;   
    private boolean suspended;          
 
    protected User(String id, String name, String email, String passwordHash, boolean suspended) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name is required.");
        String e = email == null ? "" : email.trim().toLowerCase();
        if (!e.contains("@") || e.contains("|") || e.contains(" ")) throw new IllegalArgumentException("A valid email is required.");
        this.id = id;
        this.name = clean(name);
        this.email = e;
        this.passwordHash = passwordHash;
        this.suspended = suspended;
    }
 
    public String getId()       { return id; }
    public String getName()     { return name; }
    public String getEmail()    { return email; }
    public boolean isSuspended(){ return suspended; }

    public void setName(String newName) {
        if (newName == null || newName.isBlank()) throw new IllegalArgumentException("Name cannot be empty.");
        this.name = clean(newName);
    }
 
    public void suspend()   { this.suspended = true; }    
    public void reinstate() { this.suspended = false; }   

    public boolean checkPassword(String attempt) {
        return passwordHash.equals(hash(email, attempt));
    }
 
    public abstract String getRole();            
    public abstract String getDashboardName();    
    public abstract boolean canPostRequests(); 
    protected abstract String extraFields();      
 
    public String greeting() {
        return "Hello, " + name + " (" + getRole() + ")";
    }
 
    @Override
    public String toRecord() {
        return String.join("|", getRole(), id, name, email, passwordHash, String.valueOf(suspended), extraFields());
    }
 
    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
 
    public static String hash(String email, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((email.trim().toLowerCase() + ":" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String clean(String text) {
        return text == null ? "" : text.replace("|", "/").replace("\n", " ").replace("\r", " ").trim();
    }
}
