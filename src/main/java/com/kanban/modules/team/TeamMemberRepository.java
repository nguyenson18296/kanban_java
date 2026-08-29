package com.kanban.modules.team;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMemberId> {
  Optional<TeamMember> findByTeamIdAndUserId(Integer teamId, String userId);

  @EntityGraph(attributePaths = "user")
  @Query("select m from TeamMember m where m.teamId = :teamId and m.projectId = :projectId order by m.joinedAt asc")
  List<TeamMember> findByTeamIdAndProjectIdWithUserOrderByJoinedAtAsc(@Param("teamId") Integer teamId,
      @Param("projectId") String projectId);

  @Transactional
  @Modifying
  @Query("delete from TeamMember m where m.teamId = :teamId and m.userId = :userId")
  int deleteByTeamIdAndUserId(@Param("teamId") Integer teamId, @Param("userId") String userId);

  @Transactional
  @Modifying
  @Query("delete from TeamMember m where m.projectId = :projectId and m.userId in :userIds")
  int deleteByProjectIdAndUserIdIn(@Param("projectId") String projectId,
      @Param("userIds") Collection<String> userIds);
}
