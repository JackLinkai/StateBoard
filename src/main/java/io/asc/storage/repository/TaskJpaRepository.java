package io.asc.storage.repository;

import io.asc.storage.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * task 表的 Spring Data JPA 接口。
 *
 * <p>只暴露给 storage 层内部使用，application 层拿到的应该是 TaskStore，
 * 不允许直接摸到 JPA，否则 domain 和 JPA 的隔离就白做了。
 */
public interface TaskJpaRepository extends JpaRepository<TaskEntity, String> {
}
