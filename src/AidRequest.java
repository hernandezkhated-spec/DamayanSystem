import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class AidRequest implements Storable {
 
    public enum Category {
        FOOD("Food"), CLOTHING("Clothing"), SCHOOL_SUPPLIES("School Supplies"),
        CLEAN_WATER("Clean Water and Sanitation"), HYGIENE("Hygiene Supplies");
 
        private final String label;
        Category(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
 
    public enum Status { WAITING, DONATED, DELIVERED, RECEIVED }
 
    private final String id;
    private final String orgId;
    private final String orgName;
    private final Category category;
    private final String item;
    private final int quantity;
    private final String unit;
    private final String details;
    private final LocalDate datePosted;
    private Status status;
    private String donorId = "";
    private String donorName = "";
    private LocalDate dateDonated;
    private LocalDate dateDelivered;
    private LocalDate dateReceived;
 
    private AidRequest(String id, String orgId, String orgName, Category category, String item,
                       int quantity, String unit, String details, LocalDate datePosted, Status status) {
        this.id = id; this.orgId = orgId; this.orgName = orgName; this.category = category;
        this.item = item; this.quantity = quantity; this.unit = unit; this.details = details;
        this.datePosted = datePosted; this.status = status;
    }

    public static AidRequest create(Organization org, Category category, String item, int quantity, String unit, String details) {
        if (item == null || item.isBlank()) throw new IllegalArgumentException("Please say what you need.");
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be more than 0.");
        return new AidRequest(User.newId(), org.getId(), org.getName(), category, User.clean(item),
                quantity, User.clean(unit), User.clean(details), LocalDate.now(), Status.WAITING);
    }
 
    public String getId()            { return id; }
    public String getOrgId()         { return orgId; }
    public String getOrgName()       { return orgName; }
    public Category getCategory()    { return category; }
    public String getItem()          { return item; }
    public int getQuantity()         { return quantity; }
    public String getUnit()          { return unit; }
    public String getDetails()       { return details; }
    public LocalDate getDatePosted() { return datePosted; }
    public Status getStatus()        { return status; }
    public String getDonorId()       { return donorId; }
    public String getDonorName()     { return donorName; }
    public long getDaysWaiting()     { return ChronoUnit.DAYS.between(datePosted, LocalDate.now()); }

    public void markDonated(Donor donor) {
        require(Status.WAITING, "Only a Waiting request can be donated to.");
        this.donorId = donor.getId();
        this.donorName = donor.getName();
        this.dateDonated = LocalDate.now();
        this.status = Status.DONATED;
    }
 
    public void markDelivered(Donor donor) {
        require(Status.DONATED, "Only a Donated request can be marked Delivered.");
        if (!donor.getId().equals(donorId)) throw new IllegalStateException("Only the donor of this request can mark it delivered.");
        this.dateDelivered = LocalDate.now();
        this.status = Status.DELIVERED;
    }
 
    public void confirmReceived(Organization org) {
        require(Status.DELIVERED, "Only a Delivered request can be confirmed.");
        if (!org.getId().equals(orgId)) throw new IllegalStateException("Only the organization that posted this request can confirm it.");
        this.dateReceived = LocalDate.now();
        this.status = Status.RECEIVED;
    }
 
    private void require(Status expected, String message) {
        if (status != expected) throw new IllegalStateException(message + " (It is " + status + ".)");
    }
 
    @Override
    public String toRecord() {
        return String.join("|", id, orgId, User.clean(orgName), category.name(), item, String.valueOf(quantity),
                unit, details, datePosted.toString(), status.name(), donorId, User.clean(donorName),
                str(dateDonated), str(dateDelivered), str(dateReceived));
    }
 
    public static AidRequest fromRecord(String line) {
        String[] p = line.split("\\|", -1);
        AidRequest r = new AidRequest(p[0], p[1], p[2], Category.valueOf(p[3]), p[4], Integer.parseInt(p[5]),
                p[6], p[7], LocalDate.parse(p[8]), Status.valueOf(p[9]));
        r.donorId = p[10]; r.donorName = p[11];
        r.dateDonated = date(p[12]); r.dateDelivered = date(p[13]); r.dateReceived = date(p[14]);
        return r;
    }
 
    private static String str(LocalDate d)   { return d == null ? "" : d.toString(); }
    private static LocalDate date(String s)  { return s.isEmpty() ? null : LocalDate.parse(s); }
}
 