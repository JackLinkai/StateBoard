package io.asc.storage.repository;

import io.asc.storage.entity.StateCardVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * state_card_version 表的 Spring Data JPA 接口。
 *
 * <p>复合主键 (task_id, version)，见 StateCardVersionEntity.PK。
 * 供后续编译器写状态卡快照、回放 API 按版本取历史卡使用。
 */
public interface StateCardVersionJpaRepository
        extends JpaRepository<StateCardVersionEntity, StateCardVersionEntity.PK> {

    /** 取某任务最新一版状态卡。 */
    Optional<StateCardVersionEntity> findTopByTaskIdOrderByVersionDesc(String taskId);

    /** 取某任务全部已持久化的版本，版本号升序（回放面板列版本用）。 */
    List<StateCardVersionEntity> findByTaskIdOrderByVersionAsc(String taskId);
}
