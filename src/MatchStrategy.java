import java.util.List;
import java.util.Optional;

public interface MatchStrategy {
    String name();
    Optional<AidRequest> pick(List<AidRequest> waitingRequests);
}