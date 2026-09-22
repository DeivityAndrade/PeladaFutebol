package br.com.pelada.domain;

import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class Store {

  @PersistenceContext
  private EntityManager em;

  public <T> T get(Class<T> type, UUID id) {
    T entity = em.find(type, id);
    if (entity == null) throw ApiException.notFound();
    return entity;
  }

  public <T> T lock(Class<T> type, UUID id) {
    T entity = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    if (entity == null) throw ApiException.notFound();
    return entity;
  }

  public <T> T save(T entity) {
    em.persist(entity);
    return entity;
  }

  public void remove(Object entity) {
    em.remove(entity);
  }

  public void flush() {
    em.flush();
  }

  public <T> List<T> list(Class<T> type, String jpql, Object... pairs) {
    TypedQuery<T> query = em.createQuery(jpql, type);
    for (int i = 0; i < pairs.length; i += 2) query.setParameter(
      (String) pairs[i],
      pairs[i + 1]
    );
    return query.getResultList();
  }

  public <T> Optional<T> first(Class<T> type, String jpql, Object... pairs) {
    return list(type, jpql, pairs).stream().findFirst();
  }
}
