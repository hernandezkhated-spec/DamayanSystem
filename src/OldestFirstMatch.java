import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class OldestFirstMatch implements MatchStrategy {
    @Override public String name() { return "Longest waiting"; }

    @Override
    public Optional<AidRequest> pick(List<AidRequest> waiting) {
        return waiting.stream().min(Comparator.comparing(AidRequest::getDatePosted));
    }
}