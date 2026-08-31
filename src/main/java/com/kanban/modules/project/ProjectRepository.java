package com.kanban.modules.project;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, String> {
  @EntityGraph(attributePaths = "creator")
  @Query("select p from Project p order by p.createdAt desc")
  List<Project> findAllWithCreatorOrderByCreatedAtDesc();

  @EntityGraph(attributePaths = "creator")
  @Query("select p from Project p where p.id = :id")
  Optional<Project> findByIdWithCreator(@Param("id") String id);

  @Query("select p.tag from Project p where p.tag like :prefix")
  List<String> findTagsStartingWith(@Param("prefix") String prefix);

  /**
   * Locks the project row ({@code SELECT ... FOR UPDATE}) to serialize membership
   * mutations per project. Must run inside a transaction.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Project p where p.id = :id")
  Optional<Project> findByIdForUpdate(@Param("id") String id);
}
