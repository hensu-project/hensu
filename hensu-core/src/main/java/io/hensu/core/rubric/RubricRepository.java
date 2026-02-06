package io.hensu.core.rubric;

import io.hensu.core.rubric.model.Rubric;
import java.util.List;
import java.util.Optional;

/// Repository interface for rubric storage. Pure interface - implementations can use any storage.
public interface RubricRepository {

    /// Find rubric by ID.
    Optional<Rubric> findById(String id);

    /// Save rubric.
    void save(Rubric rubric);

    /// Delete rubric.
    void delete(String id);

    /// Find all rubrics.
    List<Rubric> findAll();

    /// Check if rubric exists.
    boolean exists(String id);
}
