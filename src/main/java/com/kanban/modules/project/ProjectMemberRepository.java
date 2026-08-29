package com.kanban.modules.project;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {
  Optional<ProjectMember> findByProjectIdAndUserId(String projectId, String userId);

  boolean existsByProjectIdAndUserId(String projectId, String userId);

  @Query("select m.projectId from ProjectMember m where m.userId = :userId")
  List<String> findProjectIdsByUserId(@Param("userId") String userId);

  @EntityGraph(attributePaths = "user")
  @Query("select m from ProjectMember m where m.projectId = :projectId order by m.joinedAt asc")
  List<ProjectMember> findByProjectIdWithUserOrderByJoinedAtAsc(@Param("projectId") String projectId);

  @EntityGraph(attributePaths = {"project", "project.creator"})
  @Query("select m from ProjectMember m where m.userId = :userId order by m.joinedAt desc")
  List<ProjectMember> findByUserIdWithProjectOrderByJoinedAtDesc(@Param("userId") String userId);

  List<ProjectMember> findByProjectIdAndUserIdIn(String projectId, Collection<String> userIds);

  @Transactional
  @Modifying
  @Query("delete from ProjectMember m where m.projectId = :projectId and m.userId in :userIds")
  int deleteByProjectIdAndUserIdIn(@Param("projectId") String projectId,
      @Param("userIds") Collection<String> userIds);
}
