package com.kanban.modules.task;

import com.kanban.common.json.Json;
import com.kanban.modules.label.Label;
import com.kanban.modules.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "tasks")
public class Task {
  /** Relation paths must exactly match the Java relationship field names used by JPA and {@code toJson}. */
  public static final String REL_ASSIGNEES = "assignees";
  public static final String REL_LABELS = "labels";
  public static final String REL_CREATOR = "creator";
  public static final String REL_SUBTASKS = "subtasks";
  public static final String REL_SUBTASKS_PARENT = "subtasks.parent";
  public static final String REL_PARENT = "parent";

  /** The relation set loaded by findOneById / findByTicketId. */
  public static final Set<String> FULL_RELATIONS = Set.of(
      REL_ASSIGNEES, REL_LABELS, REL_CREATOR, REL_SUBTASKS, REL_SUBTASKS_PARENT, REL_PARENT);

  @Id
  @UuidGenerator
  @Column(columnDefinition = "uuid")
  private String id;

  @Column(nullable = false, length = 255)
  private String title;

  @Column(columnDefinition = "text")
  private String description;

  @Convert(converter = TaskStatusConverter.class)
  @Column(nullable = false, columnDefinition = "tasks_status_enum")
  private TaskStatus status = TaskStatus.OPEN;

  @Convert(converter = TaskPriorityConverter.class)
  @Column(nullable = false, columnDefinition = "tasks_priority_enum")
  private TaskPriority priority = TaskPriority.NO_PRIORITY;

  @Column(nullable = false)
  private int position = 0;

  /** Set by the {@code trg_tasks_set_ticket_id} trigger on insert. */
  @Generated(event = EventType.INSERT)
  @Column(name = "ticket_id", length = 20, unique = true, insertable = false, updatable = false)
  private String ticketId;

  @Generated(event = EventType.INSERT)
  @Column(name = "ticket_number", insertable = false, updatable = false)
  private Integer ticketNumber;

  @Column(name = "column_id", nullable = false)
  private Integer columnId;

  @Column(name = "team_id")
  private Integer teamId;

  @Column(name = "created_by", columnDefinition = "uuid")
  private String createdBy;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by", insertable = false, updatable = false)
  private User creator;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(name = "task_assignees",
      joinColumns = @JoinColumn(name = "task_id"),
      inverseJoinColumns = @JoinColumn(name = "user_id"))
  private Set<User> assignees = new LinkedHashSet<>();

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(name = "task_labels",
      joinColumns = @JoinColumn(name = "task_id"),
      inverseJoinColumns = @JoinColumn(name = "label_id"))
  private Set<Label> labels = new LinkedHashSet<>();

  @Column(name = "parent_id", columnDefinition = "uuid")
  private String parentId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id", insertable = false, updatable = false)
  private Task parent;

  @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
  private Set<Task> subtasks = new LinkedHashSet<>();

  @Column(name = "due_date")
  private Instant dueDate;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * TypeORM {@code toJSON()} — drops {@code created_by}; emits loaded relations
   * only; {@code parent} is a summary when loaded, {@code { id }} when only the
   * FK is known, and absent for top-level tasks whose relation wasn't loaded.
   */
  public Map<String, Object> toJson(Set<String> relations) {
    Map<String, Object> json = Json.map(
        "id", id,
        "title", title,
        "description", description,
        "status", status,
        "priority", priority,
        "position", position,
        "ticket_id", ticketId,
        "ticket_number", ticketNumber,
        "column_id", columnId,
        "team_id", teamId);
    if (relations.contains(REL_CREATOR)) {
      json.put("creator", creator == null ? null : creator.toJson());
    }
    if (relations.contains(REL_ASSIGNEES)) {
      List<Object> list = new ArrayList<>();
      for (User u : assignees) {
        list.add(u.toJson());
      }
      json.put("assignees", list);
    }
    if (relations.contains(REL_LABELS)) {
      List<Object> list = new ArrayList<>();
      for (Label l : labels) {
        list.add(l.toJson());
      }
      json.put("labels", list);
    }
    if (relations.contains(REL_SUBTASKS)) {
      List<Object> list = new ArrayList<>();
      Set<String> childRelations = relations.contains(REL_SUBTASKS_PARENT) ? Set.of(REL_PARENT) : Set.of();
      for (Task sub : subtasks) {
        list.add(sub.toJson(childRelations));
      }
      json.put("subtasks", list);
    }
    json.put("due_date", dueDate);
    json.put("created_at", createdAt);
    json.put("updated_at", updatedAt);
    json.put("parent_id", parentId);
    if (relations.contains(REL_PARENT)) {
      if (parent == null) {
        json.put("parent", null);
      } else {
        json.put("parent", Json.map(
            "ticket_id", parent.getTicketId(),
            "title", parent.getTitle(),
            "column_id", parent.getColumnId()));
      }
    } else if (parentId != null) {
      json.put("parent", Json.map("id", parentId));
    }
    return json;
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public TaskStatus getStatus() { return status; }
  public void setStatus(TaskStatus status) { this.status = status; }
  public TaskPriority getPriority() { return priority; }
  public void setPriority(TaskPriority priority) { this.priority = priority; }
  public int getPosition() { return position; }
  public void setPosition(int position) { this.position = position; }
  public String getTicketId() { return ticketId; }
  public void setTicketId(String ticketId) { this.ticketId = ticketId; }
  public Integer getTicketNumber() { return ticketNumber; }
  public void setTicketNumber(Integer ticketNumber) { this.ticketNumber = ticketNumber; }
  public Integer getColumnId() { return columnId; }
  public void setColumnId(Integer columnId) { this.columnId = columnId; }
  public Integer getTeamId() { return teamId; }
  public void setTeamId(Integer teamId) { this.teamId = teamId; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public User getCreator() { return creator; }
  public void setCreator(User creator) { this.creator = creator; }
  public Set<User> getAssignees() { return assignees; }
  public void setAssignees(Set<User> assignees) { this.assignees = assignees; }
  public Set<Label> getLabels() { return labels; }
  public void setLabels(Set<Label> labels) { this.labels = labels; }
  public String getParentId() { return parentId; }
  public void setParentId(String parentId) { this.parentId = parentId; }
  public Task getParent() { return parent; }
  public void setParent(Task parent) { this.parent = parent; }
  public Set<Task> getSubtasks() { return subtasks; }
  public void setSubtasks(Set<Task> subtasks) { this.subtasks = subtasks; }
  public Instant getDueDate() { return dueDate; }
  public void setDueDate(Instant dueDate) { this.dueDate = dueDate; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
