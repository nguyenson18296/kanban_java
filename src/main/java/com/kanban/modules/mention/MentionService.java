package com.kanban.modules.mention;

import com.kanban.common.util.ParseMentions;
import com.kanban.modules.user.UserRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class MentionService {
  private final UserRepository userRepository;

  public MentionService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Parse @mentions from HTML content and resolve them to active user ids.
   * Accepts both direct UUIDs (data-mention-id) and full_names; verifies each
   * against active users and drops any id in excludeIds (e.g. the actor).
   */
  public List<String> resolveMentionedUserIds(String html, List<String> excludeIds) {
    ParseMentions.Mentions mentions = ParseMentions.parseMentions(html);
    if (mentions.ids().isEmpty() && mentions.names().isEmpty()) {
      return List.of();
    }
    Set<String> resolved = new LinkedHashSet<>();
    Set<String> exclude = new HashSet<>(excludeIds);
    if (!mentions.ids().isEmpty()) {
      for (String id : userRepository.findActiveIdsByIdIn(mentions.ids())) {
        if (!exclude.contains(id)) {
          resolved.add(id);
        }
      }
    }
    if (!mentions.names().isEmpty()) {
      for (String id : userRepository.findActiveIdsByFullNameIn(mentions.names())) {
        if (!exclude.contains(id)) {
          resolved.add(id);
        }
      }
    }
    return new ArrayList<>(resolved);
  }

  public List<String> resolveMentionedUserIds(String html) {
    return resolveMentionedUserIds(html, List.of());
  }
}
