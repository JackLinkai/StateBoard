package io.asc.storage.repository;

import io.asc.storage.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * task 表的 Spring Data JPA 接口。
 *
 * <p>只暴露给 storage 层内部使用，application 层拿到的应该是 TaskStore，
 * 不允许直接摸到 JPA，否则 domain 和 JPA 的隔离就白做了。
 */
public interface TaskJpaRepository extends JpaRepository<TaskEntity, String> {

    /** 全量任务列表，最近更新的排前面（面板 / 运维的默认视角）。 */
    List<TaskEntity> findAllByOrderByUpdatedAtDesc();

    /** 某租户的任务列表，最近更新的排前面（多租户隔离视角）。 */
    List<TaskEntity> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}
