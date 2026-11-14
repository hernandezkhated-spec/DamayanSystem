public class Organization extends User {
 
    private final String orgType;          
    private final String registrationNo;   
    private String proofDocument;          
    private VerificationStatus status;     
    private String rejectionReason;
 
    public Organization(String id, String name, String email, String passwordHash, boolean suspended,
                        String orgType, String registrationNo, String proofDocument,
                        VerificationStatus status, String rejectionReason) {
        super(id, name, email, passwordHash, suspended);
        this.orgType = orgType;
        this.registrationNo = registrationNo;
        this.proofDocument = proofDocument;
        this.status = status;
        this.rejectionReason = rejectionReason == null ? "" : rejectionReason;
    }
 
    public static Organization register(String name, String email, String password, String orgType,
                                        String registrationNo, String proofDocument) {
        if (password == null || password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters.");
        if (registrationNo == null || registrationNo.isBlank()) throw new IllegalArgumentException("Registration number is required.");
        if (proofDocument == null || proofDocument.isBlank()) throw new IllegalArgumentException("Please upload a proof document.");
        return new Organization(newId(), name, email, hash(email, password), false,
                clean(orgType), clean(registrationNo), clean(proofDocument), VerificationStatus.PENDING, "");
    }
 

    public String getOrgType()               { return orgType; }
    public String getRegistrationNo()        { return registrationNo; }
    public String getProofDocument()         { return proofDocument; }
    public VerificationStatus getStatus()    { return status; }
    public String getRejectionReason()       { return rejectionReason; }

    public void markVerified() {
        requirePending("verified");
        this.status = VerificationStatus.VERIFIED;
        this.rejectionReason = "";
    }
 
    public void markRejected(String reason) {
        requirePending("rejected");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Please give a reason.");
        this.status = VerificationStatus.REJECTED;
        this.rejectionReason = clean(reason);
    }

    public void resubmit(String newProofDocument) {
        if (status != VerificationStatus.REJECTED) throw new IllegalStateException("Only a Rejected organization can resubmit. (It is " + status + ".)");
        if (newProofDocument == null || newProofDocument.isBlank()) throw new IllegalArgumentException("Please upload a proof document.");
        this.proofDocument = clean(newProofDocument);
        this.status = VerificationStatus.PENDING;
        this.rejectionReason = "";
    }
 
    private void requirePending(String action) {
        if (status != VerificationStatus.PENDING) {
            throw new IllegalStateException("Only a Pending organization can be " + action + ". (It is " + status + ".)");
        }
    }

    public AidRequest postRequest(AidRequest.Category category, String item, int quantity, String unit, String details) {
        if (!canPostRequests()) {
            String why = isSuspended() ? "Your account is suspended." : "Your organization is " + status + ".";
            throw new IllegalStateException(why + " Only active, verified organizations can post requests.");
        }
        return AidRequest.create(this, category, item, quantity, unit, details);
    }
 
    public void confirmReceived(AidRequest request) { request.confirmReceived(this); }
 
    @Override public String getRole()          { return "ORGANIZATION"; }
    @Override public String getDashboardName() { return "Organization Dashboard"; }
    @Override public boolean canPostRequests() { return status == VerificationStatus.VERIFIED && !isSuspended(); }
 
    @Override
    public String greeting() {                   
        return super.greeting() + " - " + orgType + ", " + status;
    }
 
    @Override
    protected String extraFields() {
        return String.join("|", orgType, registrationNo, proofDocument, status.name(), rejectionReason);
    }
}
 