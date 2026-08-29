package com.kanban.modules.team;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Integer> {
  Optional<Team> findByIdAndProjectId(Integer id, String projectId);

  List<Team> findByProjectIdOrderByCreatedAtAsc(String projectId);
}
