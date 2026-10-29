package io.asc.storage.repository;

import io.asc.storage.entity.StateItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * state_item 表的 Spring Data JPA 接口。
 *
 * <p>只暴露给 storage 层内部使用，application 层通过 StateItemStore 访问。
 * 按 task 查询时按 created_at、version 升序返回，
 * 保证“先写的状态先出现”，恢复任务时能按原始顺序重放。
 */
public interface StateItemJpaRepository extends JpaRepository<StateItemEntity, String> {

    /** 按任务查全部状态项，按创建时间和版本号升序。 */
    List<StateItemEntity> findByTaskIdOrderByCreatedAtAscVersionAsc(String taskId);
}
