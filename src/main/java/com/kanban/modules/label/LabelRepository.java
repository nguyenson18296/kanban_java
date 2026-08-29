package com.kanban.modules.label;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LabelRepository extends JpaRepository<Label, Integer> {
  List<Label> findByIdIn(Collection<Integer> ids);
}
