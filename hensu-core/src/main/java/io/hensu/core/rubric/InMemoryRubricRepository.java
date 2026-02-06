package io.hensu.core.rubric;

import io.hensu.core.rubric.model.Rubric;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// In-memory rubric repository (default implementation). Thread-safe, no external dependencies.
public final class InMemoryRubricRepository implements RubricRepository {

    private final Map<String, Rubric> rubrics = new ConcurrentHashMap<>();

    /// No-arg constructor for ServiceLocator.
    public InMemoryRubricRepository() {
        // Pure in-memory storage
    }

    @Override
    public Optional<Rubric> findById(String id) {
        return Optional.ofNullable(rubrics.get(id));
    }

    @Override
    public void save(Rubric rubric) {
        if (rubric == null) {
            throw new IllegalArgumentException("Rubric cannot be null");
        }
        rubrics.put(rubric.getId(), rubric);
    }

    @Override
    public void delete(String id) {
        rubrics.remove(id);
    }

    @Override
    public List<Rubric> findAll() {
        return new ArrayList<>(rubrics.values());
    }

    @Override
    public boolean exists(String id) {
        return rubrics.containsKey(id);
    }

    /// Clear all rubrics (useful for testing).
    public void clear() {
        rubrics.clear();
    }
}
