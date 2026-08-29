package com.kanban.modules.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, String> {
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  List<User> findByIdIn(Collection<String> ids);

  @Query("select u.id from User u where u.id in :ids and u.isActive = true")
  List<String> findActiveIdsByIdIn(@Param("ids") Collection<String> ids);

  @Query("select u.id from User u where u.fullName in :names and u.isActive = true")
  List<String> findActiveIdsByFullNameIn(@Param("names") Collection<String> names);
}
