package com.kanban.modules.invitation;

import org.springframework.data.jpa.repository.JpaRepository;

/** Custom finders are added in Task 8 as the service needs them. */
public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitation, String> {
}
