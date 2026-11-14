import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class LargestQuantityMatch implements MatchStrategy {
    @Override public String name() { return "Largest quantity"; }

    @Override
    public Optional<AidRequest> pick(List<AidRequest> waiting) {
        return waiting.stream().max(Comparator.comparingInt(AidRequest::getQuantity));
    }
}
    

