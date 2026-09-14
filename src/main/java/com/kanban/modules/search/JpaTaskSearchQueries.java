package com.kanban.modules.search;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class JpaTaskSearchQueries implements TaskSearchQueries {
  /** Fragment mode: up to two 6–12-word fragments around the matches, joined by " ... ". */
  private static final String HEADLINE_OPTIONS = "StartSel=" + MARK_START + ", StopSel=" + MARK_END
      + ", MaxFragments=2, MaxWords=12, MinWords=6";

  /** tasks have no project_id — scope through the column; the caller's project ids are the authorization. */
  private static final String SCOPE = " FROM tasks t JOIN kanban_columns c ON c.id = t.column_id"
      + " WHERE c.project_id IN (:projectIds) AND " + TaskSearchSql.MATCHES;

  @PersistenceContext
  private EntityManager em;

  @Override
  public List<Hit> search(List<String> projectIds, String search, int limit, long offset) {
    // ts_headline is computed in the outer query so it only runs for the page's rows, not every match.
    Query q = em.createNativeQuery(
        "SELECT hit.id, hit.project_id, ts_headline('" + TaskSearchSql.CONFIG + "',"
            + " concat_ws(' ', hit.title, hit.description), " + TaskSearchSql.TSQUERY + ", :headlineOptions) AS snippet"
            + " FROM (SELECT t.id, t.title, t.description, c.project_id, t.updated_at,"
            + " ts_rank_cd(t.search_vector, " + TaskSearchSql.TSQUERY + ") AS score"
            + SCOPE
            + " ORDER BY score DESC, t.updated_at DESC, t.id LIMIT :limit OFFSET :offset) hit"
            + " ORDER BY hit.score DESC, hit.updated_at DESC, hit.id");
    q.setParameter("projectIds", projectIds);
    q.setParameter("search", search);
    q.setParameter("headlineOptions", HEADLINE_OPTIONS);
    q.setParameter("limit", limit);
    q.setParameter("offset", offset);
    List<Hit> hits = new ArrayList<>();
    for (Object row : q.getResultList()) {
      Object[] cols = (Object[]) row;
      hits.add(new Hit(String.valueOf(cols[0]), String.valueOf(cols[1]), (String) cols[2]));
    }
    return hits;
  }

  @Override
  public long count(List<String> projectIds, String search) {
    Query q = em.createNativeQuery("SELECT COUNT(*)" + SCOPE);
    q.setParameter("projectIds", projectIds);
    q.setParameter("search", search);
    return ((Number) q.getSingleResult()).longValue();
  }
}
