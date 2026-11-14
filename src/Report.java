import java.time.LocalDate;

public class Report implements Storable {

    public enum Status { OPEN, RESOLVED }

    private final String id;
    private final String reporterId;
    private final String reporterName;
    private final String message;
    private final LocalDate datePosted;
    private Status status;

    private Report(String id, String reporterId, String reporterName, String message, LocalDate datePosted, Status status) {
        this.id = id; this.reporterId = reporterId; this.reporterName = reporterName;
        this.message = message; this.datePosted = datePosted; this.status = status;
    }

    public static Report create(User reporter, String message) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Please describe the problem.");
        return new Report(User.newId(), reporter.getId(), reporter.getName(), User.clean(message), LocalDate.now(), Status.OPEN);
    }

    public String getId()           { return id; }
    public String getReporterId()   { return reporterId; }
    public String getReporterName() { return reporterName; }
    public String getMessage()      { return message; }
    public LocalDate getDatePosted(){ return datePosted; }
    public Status getStatus()       { return status; }

    public void resolve() {
        if (status != Status.OPEN) throw new IllegalStateException("This report is already resolved.");
        this.status = Status.RESOLVED;
    }

    @Override
    public String toRecord() {
        return String.join("|", id, reporterId, User.clean(reporterName), message, datePosted.toString(), status.name());
    }

    public static Report fromRecord(String line) {
        String[] p = line.split("\\|", -1);
        return new Report(p[0], p[1], p[2], p[3], LocalDate.parse(p[4]), Status.valueOf(p[5]));
    }
}
